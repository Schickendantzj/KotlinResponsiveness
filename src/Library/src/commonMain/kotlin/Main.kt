package org.responsiveness.main

interface Main {
    fun main(args: Array<String>): Unit

    fun tryMain(): Unit {
        main(args = arrayOf<String>("arg1", "arg2"))
    }
}

object LGCFactory {
    fun start(): Unit {

    }

}

expect object MainFactory {
    fun createMain(): Main
}