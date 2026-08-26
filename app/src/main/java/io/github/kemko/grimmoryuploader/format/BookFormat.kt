package io.github.kemko.grimmoryuploader.format

import java.io.IOException

enum class BookFormat {
    FB2,
    FB2_ZIP,
    EPUB,
    PDF,
}

class UnsupportedBookException(
    message: String,
) : IOException(message)
