package com.example.teethree.ui

internal class ComposerEnterHandler {
    private var consumed = false

    fun handle(
        enter: Boolean,
        down: Boolean,
        up: Boolean,
        repeated: Boolean,
        softKeyboard: Boolean,
        behavior: String,
        shift: Boolean,
        control: Boolean,
        meta: Boolean,
        enabled: Boolean,
        send: () -> Unit,
    ): Boolean {
        if (!enter || softKeyboard) return false
        if (up) return consumed.also { consumed = false }
        if (!down) return false
        if (consumed) return true
        if (!enabled || shift || (behavior == "newline" && !control && !meta)) return false
        consumed = true
        if (!repeated) send()
        return true
    }
}
