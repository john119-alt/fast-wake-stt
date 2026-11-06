package com.fastwake.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveDataTest {

    @Test fun `detects weather questions`() {
        assertTrue(LiveData.isWeather("what's the weather in Mumbai today"))
        assertTrue(LiveData.isWeather("will it rain tomorrow?"))
        assertTrue(LiveData.isWeather("Do I need an umbrella"))
        assertFalse(LiveData.isWeather("who won the match yesterday"))
        assertFalse(LiveData.isWeather("set a reminder for nine"))
    }

    @Test fun `extracts the place from weather questions`() {
        assertEquals("Mumbai", LiveData.placeIn("what's the weather in Mumbai today"))
        assertEquals("New Delhi", LiveData.placeIn("will it rain in New Delhi tomorrow?"))
        assertEquals("Pune", LiveData.placeIn("temperature at Pune right now"))
        assertEquals("London", LiveData.placeIn("forecast for London"))
        assertEquals("San Francisco", LiveData.placeIn("Is it cold in San Francisco?"))
        assertNull(LiveData.placeIn("what's the weather today"))
        assertNull(LiveData.placeIn("will it rain in the evening"))
    }

    @Test fun `extracts the subject of encyclopedia questions`() {
        assertEquals("Virat Kohli", LiveData.lookupSubject("Who is Virat Kohli?"))
        assertEquals("Eiffel Tower", LiveData.lookupSubject("what is the Eiffel Tower"))
        assertEquals("quantum computing", LiveData.lookupSubject("tell me about quantum computing"))
        assertNull(LiveData.lookupSubject("what is the time"))
        assertNull(LiveData.lookupSubject("what's the weather in Mumbai"))
        assertNull(LiveData.lookupSubject("what is your name"))
        assertNull(LiveData.lookupSubject("what's up"))
        assertNull(LiveData.lookupSubject("set an alarm"))
    }

    @Test fun `no place and no home city asks for the city`() {
        val ctx = LiveData().contextFor("what's the weather like", homeCity = "")
        assertTrue(ctx, ctx.contains("ask which city"))
    }

    /** Real network: runs only with FASTWAKE_LIVE=1 (not in CI). */
    @Test fun `live weather and wikipedia`() {
        assumeTrue(System.getenv("FASTWAKE_LIVE") == "1")
        val ld = LiveData()
        val w = ld.contextFor("what's the weather in Mumbai today?", homeCity = "")
        println(w)
        assertTrue(w, w.contains("Mumbai") && w.contains("°C") && w.contains("Tomorrow"))
        val k = ld.contextFor("who is Virat Kohli", homeCity = "")
        println(k)
        assertTrue(k, k.contains("cricket", ignoreCase = true))
        val home = ld.contextFor("do I need an umbrella tomorrow", homeCity = "Bengaluru")
        assertTrue(home, home.contains("Bengaluru", ignoreCase = true) || home.contains("Bangalore", ignoreCase = true))
    }
}
