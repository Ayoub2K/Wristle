package dev.ayoub.wristle.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakableEndTest {
    @Test
    fun noCompleteSentenceYet() {
        assertNull(speakableEnd("It is sunny", 0))
        assertNull(speakableEnd("It is sunny.", 0))
    }

    @Test
    fun endsAfterLastCompleteSentence() {
        val text = "It is sunny. Wind is light! Tomorrow"
        assertEquals("It is sunny. Wind is light!".length, speakableEnd(text, 0))
    }

    @Test
    fun ignoresDecimalsAndStartsFromOffset() {
        val text = "Version 3.5 is out. Next"
        assertEquals("Version 3.5 is out.".length, speakableEnd(text, 0))
        assertNull(speakableEnd(text, "Version 3.5 is out.".length))
    }

    @Test
    fun newlineEndsAParagraph() {
        assertEquals("Line one\n".length, speakableEnd("Line one\n\nLine two", 0))
    }
}
