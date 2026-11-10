package com.example.locall.voice.agent

import com.example.locall.voice.agent.orchestrator.AgentTool
import com.example.locall.voice.agent.orchestrator.ToolRegistry
import com.example.locall.voice.agent.util.DateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AgentRoutingTest {

    @Test fun `brief phrases route to BriefInbox`() {
        assertEquals(AgentTool.BriefInbox, ToolRegistry.grammar("brief me"))
        assertEquals(AgentTool.BriefInbox, ToolRegistry.grammar("what's new"))
    }

    @Test fun `plan my day and task list`() {
        assertEquals(AgentTool.PlanDay, ToolRegistry.grammar("plan my day"))
        assertEquals(AgentTool.ListTasks, ToolRegistry.grammar("my tasks"))
    }

    @Test fun `add a task splits off the due phrase`() {
        val t = ToolRegistry.grammar("add a task call sam tomorrow")
        assertTrue(t is AgentTool.AddTask)
        t as AgentTool.AddTask
        assertEquals("call sam", t.title)
        assertEquals("tomorrow", t.duePhrase)
    }

    @Test fun `remind me to maps to add task`() {
        val t = ToolRegistry.grammar("remind me to buy milk")
        assertTrue(t is AgentTool.AddTask)
        assertEquals("buy milk", (t as AgentTool.AddTask).title)
    }

    @Test fun `complete and plan-goal routing`() {
        assertEquals(AgentTool.CompleteTask("the report"), ToolRegistry.grammar("complete the report"))
        val g = ToolRegistry.grammar("help me finish the website redesign")
        assertTrue(g is AgentTool.PlanGoal)
        assertEquals("the website redesign", (g as AgentTool.PlanGoal).goal)
    }

    @Test fun `study plan horizon is parsed`() {
        val g = ToolRegistry.grammar("prepare a study plan for 30 days of spanish")
        assertTrue(g is AgentTool.PlanGoal)
        assertEquals(30, (g as AgentTool.PlanGoal).horizonDays)
    }

    @Test fun `unrelated utterance is not an agent command`() {
        assertNull(ToolRegistry.grammar("play some jazz"))
    }

    @Test fun `parseJson add_task with fields`() {
        val t = ToolRegistry.parseJson("""{"tool":"add_task","title":"pay rent","due":"friday","priority":"high"}""", "x")
        assertEquals(AgentTool.AddTask("pay rent", "friday", "high"), t)
    }

    @Test fun `parseJson tolerates surrounding prose`() {
        val t = ToolRegistry.parseJson("""Sure: {"tool":"reply","ref":"sam","body":""} done""", "x")
        assertEquals(AgentTool.ReplyTo("sam", ""), t)
    }

    @Test fun `parseJson unknown tool falls back to chat`() {
        val t = ToolRegistry.parseJson("""{"tool":"fly_to_moon"}""", "take me to the moon")
        assertEquals(AgentTool.Chat("take me to the moon"), t)
    }

    @Test fun `tomorrow is within the next two days`() {
        val now = fixedNow()
        val r = DateParser.parse("tomorrow", now)!!
        assertTrue(r > now && r < now + 2 * DAY)
    }

    @Test fun `in 3 days`() {
        val now = fixedNow()
        val r = DateParser.parse("in 3 days", now)!!
        assertTrue(r in (now + 2 * DAY)..(now + 4 * DAY))
    }

    @Test fun `none yields null`() {
        assertNull(DateParser.parse("none"))
        assertNull(DateParser.parse(""))
    }

    private fun fixedNow(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.JANUARY, 15, 10, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object { const val DAY = 24L * 60 * 60 * 1000 }
}
