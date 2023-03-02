package org.responsiveness.main

import kotlinx.coroutines.channels.Channel

interface Main {
    fun main(args: Array<String>, outputChannel: Channel<String>): Unit

    fun tryMain(): Unit {
        main(args = arrayOf<String>("arg1", "arg2"), Channel<String>())
    }
}

object LGCFactory {
    fun start(): Unit {

    }

}

expect object MainFactory {
    fun createMain(): Main
}