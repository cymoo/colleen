package io.github.cymoo.colleen.server

import io.github.cymoo.colleen.Request
import io.github.cymoo.colleen.Response

typealias RequestHandler = (Request) -> Response

interface WebServer {
    /**
     * Starts the HTTP server with the provided request handler.
     *
     * @param handler function that processes requests and returns responses
     */
    fun start(handler: RequestHandler)

    /**
     * Stops the HTTP server gracefully.
     */
    fun stop()
}

