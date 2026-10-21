package ru.landilf.shellink

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalEmulatorInstrumentedTest {
    @Test fun parsesUtf8AnsiAndScreenClear() {
        val terminal = TerminalState()
        terminal.append("discard me".toByteArray(Charsets.UTF_8), "discard me".toByteArray(Charsets.UTF_8).size)
        val output = "\u001b[2J\u001b[HПривет \u001b[38;5;196mred\u001b[0m".toByteArray(Charsets.UTF_8)
        terminal.append(output, output.size)
        val screen = terminal.emulator.screen.getTranscriptText()
        assertTrue(screen.contains("Привет"))
        assertTrue(screen.contains("red"))
        assertTrue(!screen.contains("discard me"))
    }
}
