package com.example

import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testExtractVideoId() {
    val service = com.example.data.TelegramBotService
    val method = service::class.java.getDeclaredMethod("extractVideoId", String::class.java)
    method.isAccessible = true
    
    fun extract(url: String): String? {
        return method.invoke(service, url) as String?
    }
    
    assertEquals("h-Z7CEqBO3s", extract("https://m.youtube.com/watch?v=h-Z7CEqBO3s&pp=ugUEEgJlbg%3D%3D"))
    assertEquals("yY14v1n7Ivw", extract("https://youtu.be/yY14v1n7Ivw?si=JwMxWyNvW_0vd09U"))
    assertEquals("h-Z7CEqBO3s", extract("https://www.youtube.com/watch?v=h-Z7CEqBO3s&pp=ugUEEgJlbg%3D%3D"))
    assertEquals("abc123xyzAB", extract("https://youtube.com/shorts/abc123xyzAB"))
    assertEquals("abc123xyzAB", extract("https://youtube.com/embed/abc123xyzAB"))
    assertEquals("abc123xyzAB", extract("https://youtube.com/live/abc123xyzAB"))
    assertEquals("abc123xyzAB", extract("abc123xyzAB"))
  }
}
