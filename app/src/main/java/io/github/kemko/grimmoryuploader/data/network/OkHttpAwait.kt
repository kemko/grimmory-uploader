package io.github.kemko.grimmoryuploader.data.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    error: IOException,
                ) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            },
        )
    }
