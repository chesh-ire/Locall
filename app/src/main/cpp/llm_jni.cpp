#include <jni.h>
#include <sys/resource.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <android/log.h>

#include "cpu_topology.h"
#include "llama.h"

#define LOG_TAG "LlmJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

std::once_flag g_backend_init_once;

void ensure_backend_init() {
    std::call_once(g_backend_init_once, []() { llama_backend_init(); });
}

struct LlmSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
};

const char *kCommandActionGrammar = R"GBNF(
root        ::= "{" ws "\"action\"" ws ":" ws action-kind ws "," ws action-body ws "}" ws

action-kind ::= (
    "\"type_text\"" | "\"open_app\"" | "\"navigate\""
  | "\"clear_field\"" | "\"send_message\"" | "\"read_last_message\""
  | "\"unrecognized\""
)

action-body ::= (
    type-text-body | open-app-body | navigate-body
  | no-arg-body | read-last-message-body | unrecognized-body
)

type-text-body         ::= "\"text\"" ws ":" ws string
open-app-body          ::= "\"query\"" ws ":" ws string
navigate-body          ::= "\"nav\"" ws ":" ws nav-enum
no-arg-body            ::= "\"_\"" ws ":" ws "null"
read-last-message-body ::= "\"contact\"" ws ":" ws string ws "," ws "\"app\"" ws ":" ws string
unrecognized-body      ::= "\"reason\"" ws ":" ws string

nav-enum ::= (
    "\"home\"" | "\"back\"" | "\"recents\""
  | "\"notifications\"" | "\"scroll_down\"" | "\"scroll_up\""
)

string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\""
ws ::= | " " | "\n" [ \t]{0,20}
)GBNF";

CpuTopology g_topo;
bool g_topo_ready = false;

const CpuTopology &topology() {
    if (!g_topo_ready) {
        g_topo = detect_cpu_topology();
        g_topo_ready = true;
    }
    return g_topo;
}


std::string format_prompt(const std::string &system_prompt, const std::string &user_prompt) {
    std::string p = "<|start_header_id|>system<|end_header_id|>\n\n";
    p += system_prompt;
    p += "<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n";
    p += user_prompt;
    p += "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n";
    return p;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int n_needed = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_needed);
    llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n_needed, true, true);
    return tokens;
}

std::string token_to_piece(const llama_vocab *vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (n < 0) return "";
    return {buf, static_cast<size_t>(n)};
}

long read_vmrss_kb() {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return -1;
    char line[256];
    long rss_kb = -1;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "VmRSS:", 6) == 0) {
            sscanf(line + 6, "%ld", &rss_kb);
            break;
        }
    }
    fclose(f);
    return rss_kb;
}

struct GenerationStats {
    size_t prompt_tokens = 0;
    int generated_tokens = 0;
    double prefill_ms = 0;
    double decode_ms = 0;
    long rss_kb = 0;
};
GenerationStats g_last_stats;

std::string run_generation(
        LlmSession *session, const std::string &system_prompt, const std::string &user_prompt,
        const char *grammar, float temperature, int max_tokens, int decode_threads,
        bool stop_on_blank = true) {
    bool pinned = pin_current_thread_to(topology().performance_cores);
    bool prioritized = setpriority(PRIO_PROCESS, 0, -10) == 0;

    if (decode_threads > 0) {
        llama_set_n_threads(session->ctx, decode_threads, topology().suggested_batch_threads);
    }
    LOGI("pinned=%d priority boosted=%d decode_threads=%d grammar=%d", pinned, prioritized,
         decode_threads > 0 ? decode_threads : topology().suggested_decode_threads,
         grammar != nullptr);

    std::string prompt = format_prompt(system_prompt, user_prompt);
    std::vector<llama_token> tokens = tokenize(session->vocab, prompt);
    LOGI("generate: prompt=%zu tokens maxTokens=%d", tokens.size(), max_tokens);

    llama_memory_clear(llama_get_memory(session->ctx), true);

    auto t_start = std::chrono::steady_clock::now();

    const int n_batch = (int) llama_n_batch(session->ctx);
    for (size_t off = 0; off < tokens.size(); off += n_batch) {
        int n = (int) std::min((size_t) n_batch, tokens.size() - off);
        llama_batch chunk = llama_batch_get_one(tokens.data() + off, n);
        if (llama_decode(session->ctx, chunk) != 0) {
            LOGW("prefill decode failed at token %zu", off);
            return "";
        }
    }

    auto t_prefill_done = std::chrono::steady_clock::now();

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    if (grammar != nullptr && grammar[0] != '\0') {
        llama_sampler *g = llama_sampler_init_grammar(session->vocab, grammar, "root");
        if (g) {
            llama_sampler_chain_add(smpl, g);
        } else {
            LOGW("grammar failed to parse — falling back to unconstrained sampling");
        }
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(0));

    std::string out;
    int generated = 0;
    for (int i = 0; i < max_tokens; i++) {
        llama_token next = llama_sampler_sample(smpl, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, next)) break;

        out += token_to_piece(session->vocab, next);
        generated++;

        if (out.find("<|start_header_id|>") != std::string::npos ||
            (stop_on_blank && out.find("\n\n") != std::string::npos)) {
            break;
        }

        llama_batch step = llama_batch_get_one(&next, 1);
        if (llama_decode(session->ctx, step) != 0) {
            LOGW("decode step failed");
            break;
        }
    }

    auto t_decode_done = std::chrono::steady_clock::now();
    llama_sampler_free(smpl);

    double prefill_ms = std::chrono::duration<double, std::milli>(t_prefill_done - t_start).count();
    double decode_ms = std::chrono::duration<double, std::milli>(t_decode_done - t_prefill_done).count();
    double tok_per_sec = decode_ms > 0 ? (generated * 1000.0 / decode_ms) : 0.0;
    long rss_kb = read_vmrss_kb();
    g_last_stats = {tokens.size(), generated, prefill_ms, decode_ms, rss_kb};

    LOGI("generate: produced %zu chars, %d tokens — prefill=%.0fms decode=%.0fms (%.1f tok/s) rss=%ldMB",
         out.size(), generated, prefill_ms, decode_ms, tok_per_sec, rss_kb / 1024);
    return out;
}

}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_locall_voice_NativeThreads_nativePinToEfficiencyCores(JNIEnv *, jobject) {
    return pin_current_thread_to(topology().efficiency_cores) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_example_locall_voice_LlmBridge_nativeLoad(JNIEnv *env, jobject, jstring modelPath) {
    ensure_backend_init();

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!model) {
        LOGW("model load failed");
        return 0;
    }

    const CpuTopology &t = topology();
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_batch = 256;
    cparams.n_ubatch = 256;
    cparams.n_threads = t.suggested_decode_threads;
    cparams.n_threads_batch = t.suggested_batch_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGW("context init failed");
        llama_model_free(model);
        return 0;
    }

    auto *session = new LlmSession();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    LOGI("loaded model: n_ctx=2048 threads(decode=%d batch=%d) rss=%ldMB",
         t.suggested_decode_threads, t.suggested_batch_threads, read_vmrss_kb() / 1024);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL
Java_com_example_locall_voice_LlmBridge_nativeGenerate(
        JNIEnv *env, jobject,
        jlong handle, jstring systemPrompt, jstring userPrompt,
        jboolean useCommandGrammar, jfloat temperature, jint maxTokens,
        jint decodeThreads) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (!session) return env->NewStringUTF("");

    const char *sys_chars = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *user_chars = env->GetStringUTFChars(userPrompt, nullptr);
    std::string out = run_generation(
            session, sys_chars, user_chars,
            useCommandGrammar ? kCommandActionGrammar : nullptr,
            temperature, maxTokens, decodeThreads);
    env->ReleaseStringUTFChars(systemPrompt, sys_chars);
    env->ReleaseStringUTFChars(userPrompt, user_chars);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_example_locall_voice_LlmBridge_nativeGenerateLong(
        JNIEnv *env, jobject,
        jlong handle, jstring systemPrompt, jstring userPrompt,
        jfloat temperature, jint maxTokens, jint decodeThreads) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (!session) return env->NewStringUTF("");

    const char *sys_chars = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *user_chars = env->GetStringUTFChars(userPrompt, nullptr);
    std::string out = run_generation(
            session, sys_chars, user_chars, nullptr,
            temperature, maxTokens, decodeThreads, false);
    env->ReleaseStringUTFChars(systemPrompt, sys_chars);
    env->ReleaseStringUTFChars(userPrompt, user_chars);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_example_locall_voice_LlmBridge_nativeGenerateWithGrammar(
        JNIEnv *env, jobject,
        jlong handle, jstring systemPrompt, jstring userPrompt, jstring grammarGbnf,
        jfloat temperature, jint maxTokens, jint decodeThreads) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (!session) return env->NewStringUTF("");

    const char *sys_chars = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *user_chars = env->GetStringUTFChars(userPrompt, nullptr);
    const char *grammar_chars = grammarGbnf ? env->GetStringUTFChars(grammarGbnf, nullptr) : nullptr;
    std::string out = run_generation(
            session, sys_chars, user_chars, grammar_chars, temperature, maxTokens, decodeThreads);
    if (grammar_chars) env->ReleaseStringUTFChars(grammarGbnf, grammar_chars);
    env->ReleaseStringUTFChars(systemPrompt, sys_chars);
    env->ReleaseStringUTFChars(userPrompt, user_chars);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_example_locall_voice_LlmBridge_nativeLastStats(JNIEnv *env, jobject) {
    double tok_per_sec = g_last_stats.decode_ms > 0
            ? (g_last_stats.generated_tokens * 1000.0 / g_last_stats.decode_ms) : 0.0;
    char buf[256];
    snprintf(buf, sizeof(buf),
             "%d tok in %.1fs (%.1f tok/s) · prefill %.0fms (%zu tok) · RSS %ldMB",
             g_last_stats.generated_tokens, g_last_stats.decode_ms / 1000.0, tok_per_sec,
             g_last_stats.prefill_ms, g_last_stats.prompt_tokens, g_last_stats.rss_kb / 1024);
    return env->NewStringUTF(buf);
}

JNIEXPORT jstring JNICALL
Java_com_example_locall_voice_LlmBridge_nativeLastMetrics(JNIEnv *env, jobject) {
    char buf[160];
    snprintf(buf, sizeof(buf), "%zu,%d,%.3f,%.3f,%ld",
             g_last_stats.prompt_tokens, g_last_stats.generated_tokens,
             g_last_stats.prefill_ms, g_last_stats.decode_ms, g_last_stats.rss_kb);
    return env->NewStringUTF(buf);
}

JNIEXPORT void JNICALL
Java_com_example_locall_voice_LlmBridge_nativeRelease(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (!session) return;
    if (session->ctx) llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
}


JNIEXPORT jstring JNICALL
Java_com_example_locall_voice_LlmBridge_nativeModelInfo(JNIEnv *env, jobject, jlong handle) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (!session) return env->NewStringUTF("");
    char desc[256] = {0};
    llama_model_desc(session->model, desc, sizeof(desc));
    uint64_t size_bytes = llama_model_size(session->model);
    uint64_t n_params = llama_model_n_params(session->model);
    int32_t n_meta = llama_model_meta_count(session->model);

    std::string out;
    char buf[512];
    snprintf(buf, sizeof(buf), "desc=%s size_mb=%.1f n_params=%.0fM n_meta=%d rss_mb=%ld\n",
             desc, size_bytes / (1024.0 * 1024.0), n_params / 1e6, n_meta, read_vmrss_kb() / 1024);
    out += buf;
    for (int32_t i = 0; i < n_meta; i++) {
        char key[256] = {0};
        char val[512] = {0};
        llama_model_meta_key_by_index(session->model, i, key, sizeof(key));
        llama_model_meta_val_str_by_index(session->model, i, val, sizeof(val));
        snprintf(buf, sizeof(buf), "%s=%s\n", key, val);
        out += buf;
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_example_locall_voice_LlmBridge_nativeRssKb(JNIEnv *, jobject) {
    return (jlong) read_vmrss_kb();
}

JNIEXPORT jint JNICALL
Java_com_example_locall_voice_LlmBridge_nativeCountTokens(
        JNIEnv *env, jobject, jlong handle, jstring systemPrompt, jstring userPrompt) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (!session) return -1;
    const char *sys_chars = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *user_chars = env->GetStringUTFChars(userPrompt, nullptr);
    std::string prompt = format_prompt(sys_chars, user_chars);
    env->ReleaseStringUTFChars(systemPrompt, sys_chars);
    env->ReleaseStringUTFChars(userPrompt, user_chars);
    return (jint) tokenize(session->vocab, prompt).size();
}

JNIEXPORT jstring JNICALL
Java_com_example_locall_voice_LlmBridge_nativeGenerateBenchmark(
        JNIEnv *env, jobject,
        jlong handle, jstring systemPrompt, jstring userPrompt,
        jint maxTokens, jint decodeThreads, jint batchThreads, jintArray affinityCores) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (!session) return env->NewStringUTF("0,0,0,0");

    jsize n_cores = env->GetArrayLength(affinityCores);
    jint *core_elems = env->GetIntArrayElements(affinityCores, nullptr);
    std::vector<int> cores(core_elems, core_elems + n_cores);
    env->ReleaseIntArrayElements(affinityCores, core_elems, JNI_ABORT);

    bool pinned = pin_current_thread_to(cores);
    llama_set_n_threads(session->ctx, decodeThreads, batchThreads);
    LOGI("benchmark: pinned=%d decode_threads=%d batch_threads=%d cores=%d",
         pinned, decodeThreads, batchThreads, n_cores);

    const char *sys_chars = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *user_chars = env->GetStringUTFChars(userPrompt, nullptr);
    std::string prompt = format_prompt(sys_chars, user_chars);
    env->ReleaseStringUTFChars(systemPrompt, sys_chars);
    env->ReleaseStringUTFChars(userPrompt, user_chars);

    std::vector<llama_token> tokens = tokenize(session->vocab, prompt);

    llama_memory_clear(llama_get_memory(session->ctx), true);

    auto t_start = std::chrono::steady_clock::now();
    llama_batch prefill = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(session->ctx, prefill) != 0) {
        LOGW("benchmark: prefill decode failed");
        return env->NewStringUTF("0,0,0,0");
    }
    auto t_prefill_done = std::chrono::steady_clock::now();

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(0));

    int generated = 0;
    for (int i = 0; i < maxTokens; i++) {
        llama_token next = llama_sampler_sample(smpl, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, next)) break;
        generated++;
        llama_batch step = llama_batch_get_one(&next, 1);
        if (llama_decode(session->ctx, step) != 0) break;
    }
    auto t_decode_done = std::chrono::steady_clock::now();
    llama_sampler_free(smpl);

    double prefill_ms = std::chrono::duration<double, std::milli>(t_prefill_done - t_start).count();
    double decode_ms = std::chrono::duration<double, std::milli>(t_decode_done - t_prefill_done).count();
    long rss_kb = read_vmrss_kb();

    char buf[128];
    snprintf(buf, sizeof(buf), "%.1f,%.1f,%d,%ld", prefill_ms, decode_ms, generated, rss_kb);
    return env->NewStringUTF(buf);
}

JNIEXPORT jlong JNICALL
Java_com_example_locall_voice_LlmBridge_nativeLoadWithCtx(
        JNIEnv *env, jobject, jstring modelPath, jint nCtx) {
    ensure_backend_init();
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!model) return 0;

    const CpuTopology &t = topology();
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx;
    cparams.n_batch = (uint32_t) std::min(nCtx, 4096);
    cparams.n_ubatch = 512;
    cparams.n_threads = t.suggested_decode_threads;
    cparams.n_threads_batch = t.suggested_batch_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    auto *session = new LlmSession();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    LOGI("nativeLoadWithCtx: n_ctx=%d n_batch=%u rss=%ldMB", nCtx, cparams.n_batch, read_vmrss_kb() / 1024);
    return reinterpret_cast<jlong>(session);
}

}
