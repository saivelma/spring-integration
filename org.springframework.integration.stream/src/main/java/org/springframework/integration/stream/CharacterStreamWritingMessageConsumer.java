/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.stream;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.integration.core.Message;
import org.springframework.integration.core.MessagingException;
import org.springframework.integration.message.MessageConsumer;
import org.springframework.util.Assert;

/**
 * A {@link MessageConsumer} that writes characters to a {@link Writer}.
 * String, character array, and byte array payloads will be written directly,
 * but for other payload types, the result of the object's {@link #toString()}
 * method will be written. To append a new-line after each write, set the
 * {@link #shouldAppendNewLine} flag to 'true'. It is 'false' by default.
 * 
 * @author Mark Fisher
 */
public class CharacterStreamWritingMessageConsumer implements MessageConsumer {

	private final Log logger = LogFactory.getLog(this.getClass());

	private final BufferedWriter writer;

	private volatile boolean shouldAppendNewLine = false;


	public CharacterStreamWritingMessageConsumer(Writer writer) {
		this(writer, -1);
	}

	public CharacterStreamWritingMessageConsumer(Writer writer, int bufferSize) {
		Assert.notNull(writer, "writer must not be null");
		if (writer instanceof BufferedWriter) {
			this.writer = (BufferedWriter) writer;
		}
		else if (bufferSize > 0) {
			this.writer = new BufferedWriter(writer, bufferSize);
		}
		else {
			this.writer = new BufferedWriter(writer);
		}
	}


	/**
	 * Factory method that creates a target for stdout (System.out) with the
	 * default charset encoding.
	 */
	public static CharacterStreamWritingMessageConsumer stdout() {
		return stdout(null);
	}

	/**
	 * Factory method that creates a target for stdout (System.out) with the
	 * specified charset encoding.
	 */
	public static CharacterStreamWritingMessageConsumer stdout(String charsetName) {
		return createTargetForStream(System.out, charsetName);
	}

	/**
	 * Factory method that creates a target for stderr (System.err) with the
	 * default charset encoding.
	 */
	public static CharacterStreamWritingMessageConsumer stderr() {
		return stderr(null);
	}

	/**
	 * Factory method that creates a target for stderr (System.err) with the
	 * specified charset encoding.
	 */	
	public static CharacterStreamWritingMessageConsumer stderr(String charsetName) {
		return createTargetForStream(System.err, charsetName);
	}

	private static CharacterStreamWritingMessageConsumer createTargetForStream(OutputStream stream, String charsetName) {
		if (charsetName == null) {
			return new CharacterStreamWritingMessageConsumer(new OutputStreamWriter(stream));
		}
		try {
			return new CharacterStreamWritingMessageConsumer(new OutputStreamWriter(stream, charsetName));
		}
		catch (UnsupportedEncodingException e) {
			throw new IllegalArgumentException("unsupported encoding: " + charsetName, e);
		}
	}


	public void setShouldAppendNewLine(boolean shouldAppendNewLine) {
		this.shouldAppendNewLine = shouldAppendNewLine;
	}

	public void onMessage(Message<?> message) {
		Object payload = message.getPayload();
		if (payload == null) {
			if (logger.isWarnEnabled()) {
				logger.warn("target received null payload");
			}
			return;
		}
		try {
			if (payload instanceof String) {
				writer.write((String) payload);
			}
			else if (payload instanceof char[]) {
				this.writer.write((char[]) payload);
			}
			else if (payload instanceof byte[]) {
				this.writer.write(new String((byte[]) payload));
			}
			else {
				writer.write(payload.toString());
			}
			if (this.shouldAppendNewLine) {
				writer.newLine();
			}
			writer.flush();
		}
		catch (IOException e) {
			throw new MessagingException("IO failure occurred in target", e);
		}
	}

}
