package org.responsiveness.main

import kotlin.test.Test

class MainTest {
    @Test
    fun testMain() {
        MainFactory.createMain().tryMain()
    }
}