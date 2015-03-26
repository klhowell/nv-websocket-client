/*
 * Copyright (C) 2015 Neo Visionaries Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.neovisionaries.ws.client;


import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class WebSocket implements Closeable
{
    private static final String ACCEPT_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final SecureRandom sRandom = new SecureRandom();
    private final Socket mSocket;
    private final OpeningHandshakeBuilder mOpeningHandshakeBuilder;
    private final List<WebSocketListener> mListeners = new ArrayList<WebSocketListener>();
    private WebSocketInputStream mInput;
    private WebSocketOutputStream mOutput;


    WebSocket(String userInfo, String host, String path, Socket socket)
    {
        mSocket = socket;
        mOpeningHandshakeBuilder =
            new OpeningHandshakeBuilder(userInfo, host, path);
    }


    /**
     * Add a value for Sec-WebSocket-Protocol.
     */
    public WebSocket addProtocol(String protocol)
    {
        mOpeningHandshakeBuilder.addProtocol(protocol);

        return this;
    }


    /**
     * Add a value for Sec-WebSocket-Extension.
     */
    public WebSocket addExtension(String extension)
    {
        mOpeningHandshakeBuilder.addExtension(extension);

        return this;
    }


    /**
     * Add a pair of HTTP header.
     */
    public WebSocket addHeader(String name, String value)
    {
        mOpeningHandshakeBuilder.addHeader(name, value);

        return this;
    }


    /**
     * Add a listener to receive events on this web socket.
     */
    public WebSocket addListener(WebSocketListener listener)
    {
        if (listener == null)
        {
            return this;
        }

        synchronized (mListeners)
        {
            mListeners.add(listener);
        }

        return this;
    }


    /**
     * Get the raw socket which this web socket uses internally.
     */
    public Socket getSocket()
    {
        return mSocket;
    }


    public void connect() throws WebSocketException
    {
        // Get the input stream of the socket.
        openInputStream();

        // Get the output stream of the socket.
        openOutputStream();

        // Generate a value for Sec-WebSocket-Key.
        String key = generateWebSocketKey();

        // Send an opening handshake to the server.
        writeOpeningHandshake(key);

        // Read the response from the server.
        readOpeningHandshake(key);
    }


    private void openInputStream() throws WebSocketException
    {
        try
        {
            // Get the input stream of the raw socket through which
            // this client receives data from the server.
            mInput = new WebSocketInputStream(
                new BufferedInputStream(mSocket.getInputStream()));
        }
        catch (IOException e)
        {
            // Failed to get the input stream of the raw socket.
            throw new WebSocketException(
                WebSocketError.SOCKET_INPUT_STREAM_FAILURE,
                "Failed to get the input stream of the raw socket.", e);
        }
    }


    private void openOutputStream() throws WebSocketException
    {
        try
        {
            // Get the output stream of the socket through which
            // this client sends data to the server.
            mOutput = new WebSocketOutputStream(
                new BufferedOutputStream(mSocket.getOutputStream()));
        }
        catch (IOException e)
        {
            // Failed to get the output stream from the raw socket.
            throw new WebSocketException(
                WebSocketError.SOCKET_OUTPUT_STREAM_FAILURE,
                "Failed to get the output stream from the raw socket.", e);
        }
    }


    /**
     * Generate a value for Sec-WebSocket-Key.
     *
     * <blockquote>
     * <p><i>
     * The request MUST include a header field with the name Sec-WebSocket-Key.
     * The value of this header field MUST be a nonce consisting of a randomly
     * selected 16-byte value that has been base64-encoded (see Section 4 of
     * RFC 4648). The nonce MUST be selected randomly for each connection.
     * </i></p>
     * </blockquote>
     *
     * @return
     */
    private static String generateWebSocketKey()
    {
        // "16-byte value"
        byte[] data = new byte[16];

        // "randomly selected"
        sRandom.nextBytes(data);

        // "base64-encoded"
        return Base64.encode(data);
    }


    private void writeOpeningHandshake(String key) throws WebSocketException
    {
        // Generate an opening handshake sent to the server from this client.
        mOpeningHandshakeBuilder.setKey(key);
        String handshake = mOpeningHandshakeBuilder.build();

        try
        {
            // Send the opening handshake to the server.
            mOutput.write(handshake);
            mOutput.flush();
        }
        catch (IOException e)
        {
            // Failed to send an opening handshake request to the server.
            throw new WebSocketException(
                WebSocketError.OPENING_HAHDSHAKE_REQUEST_FAILURE,
                "Failed to send an opening handshake request to the server.", e);
        }
    }


    private void readOpeningHandshake(String key) throws WebSocketException
    {
        // Read the status line.
        readStatusLine();

        // Read HTTP headers.
        Map<String, List<String>> headers = readHttpHeaders();

        // Get the values of Sec-WebSocket-Accept.
        List<String> values = headers.get("SEC-WEBSOCKET-ACCEPT");

        if (values == null)
        {
            // The opening handshake response does not contain Sec-WebSocket-Accept.
            throw new WebSocketException(
                WebSocketError.NO_SEC_WEBSOCKET_ACCEPT,
                "The opening handshake response does not contain Sec-WebSocket-Accept.");
        }

        // Validate the value of Sec-WebSocket-Accept.
        validateAccept(key, values.get(0));

        // OK. The server has accepted the web socket request.
    }


    private void readStatusLine() throws WebSocketException
    {
        String line;

        try
        {
            // Read the status line.
            line = mInput.readLine();
        }
        catch (IOException e)
        {
            // Failed to read an opening handshake response from the server.
            throw new WebSocketException(
                WebSocketError.OPENING_HANDSHAKE_RESPONSE_FAILURE,
                "Failed to read an opening handshake response from the server.", e);
        }

        if (line == null || line.length() == 0)
        {
            // The status line of the opening handshake response is empty.
            throw new WebSocketException(
                WebSocketError.STATUS_LINE_EMPTY,
                "The status line of the opening handshake response is empty.");
        }

        // Expect "HTTP/1.1 101 Switching Protocols
        String[] elements = line.split(" +", 3);

        if (elements.length < 2)
        {
            // The status line of the opening handshake response is badly formatted.
            throw new WebSocketException(
                WebSocketError.STATUS_LINE_BAD_FORMAT,
                "The status line of the opening handshake response is badly formatted.");
        }

        // The status code must be 101 (Switching Protocols).
        if ("101".equals(elements[1]))
        {
            // The status code of the opening handshake response is not Switching Protocols.
            throw new WebSocketException(
                WebSocketError.NOT_SWITCHING_PROTOCOLS,
                "The status code of the opening handshake response is not Switching Protocols.");
        }

        // OK. The server can speak the WebSocket protocol.
    }


    private Map<String, List<String>> readHttpHeaders() throws WebSocketException
    {
        Map<String, List<String>> headers = new HashMap<String, List<String>>();
        StringBuilder builder = null;
        String line;

        while (true)
        {
            try
            {
                line = mInput.readLine();
            }
            catch (IOException e)
            {
                // An error occurred while HTTP header section was being read.
                throw new WebSocketException(
                    WebSocketError.HTTP_HEADER_FAILURE,
                    "An error occurred while HTTP header section was being read.", e);
            }

            // If the end of the header section was reached.
            if (line == null || line.length() == 0)
            {
                if (builder != null)
                {
                    parseHttpHeader(headers, builder.toString());
                }

                // The end of the header section.
                break;
            }

            // The first line of the line.
            char ch = line.charAt(0);

            // If the first char is SP or HT.
            if (ch == ' ' || ch == '\t')
            {
                if (builder == null)
                {
                    // Weird. No preceding "field-name:field-value" line. Ignore this line.
                    continue;
                }

                // Replacing the leading 1*(SP|HT) to a single SP.
                line = line.replaceAll("^[ \t]+", " ");

                // Concatenate
                builder.append(line);

                continue;
            }

            if (builder != null)
            {
                parseHttpHeader(headers, builder.toString());
            }

            builder = new StringBuilder(line);
        }

        return headers;
    }


    private void parseHttpHeader(Map<String, List<String>> headers, String header)
    {
        // Split 'header' to name & value.
        String[] pair = header.split(":", 2);

        if (pair.length < 2)
        {
            // Weird. Ignore this header.
            return;
        }

        // Name. (Capitalize)
        String name = pair[0].toUpperCase();

        // Value. (Remove leading spaces)
        String value = pair[1].replaceAll("^[ \t]+", "");

        List<String> list = headers.get(name);

        if (list == null)
        {
            list = new ArrayList<String>();
            headers.put(name, list);
        }

        list.add(value);
    }


    private void validateAccept(String key, String actual) throws WebSocketException
    {
        // Concatenate.
        String input = key + ACCEPT_MAGIC;

        // Expected value of Sec-WebSocket-Accept
        String expected;

        try
        {
            // Message digest for SHA-1.
            MessageDigest md = MessageDigest.getInstance("SHA-1");

            // Compute the digest value.
            byte[] digest = md.digest(input.getBytes("UTF-8"));

            // Base64.
            expected = Base64.encode(digest);
        }
        catch (Exception e)
        {
            // This never happens.
            return;
        }

        if (expected.equals(actual) == false)
        {
            // The value of Sec-WebSocket-Accept is different from the expected one.
            throw new WebSocketException(
                WebSocketError.UNEXPECTED_SEC_WEBSOCKET_ACCEPT,
                "The value of Sec-WebSocket-Accept is different from the expected one.");
        }

        // OK. The value of Sec-WebSocket-Accept is the same as the expected one.
    }


    @Override
    public void close() throws IOException
    {
        // TODO
    }
}