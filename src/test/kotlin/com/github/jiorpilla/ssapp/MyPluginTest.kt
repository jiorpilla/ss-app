package com.github.jiorpilla.ssapp

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MyPluginTest : BasePlatformTestCase() {

    fun testPluginLoads() {
        assertNotNull(project)
    }
}
