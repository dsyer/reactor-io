/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.io.netty.http.multipart;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.netty.handler.codec.http.HttpHeaders;
import reactor.core.publisher.Flux;
import reactor.io.netty.common.ByteBufEncodedFlux;
import reactor.io.netty.http.HttpInbound;

/**
 * @author Ben Hale
 */
public final class MultipartCodec {

	static final Pattern MULTIPART_PATTERN =
			Pattern.compile("multipart.*; boundary=(.+)");

	/**
	 *
	 * @param inbound
	 * @return
	 */
	public static Flux<ByteBufEncodedFlux> decode(HttpInbound inbound) {
		String boundary = extractBoundary(inbound.responseHeaders());
		return new MultipartDecoder(inbound.receive(), boundary, inbound.delegate().alloc());
	}

	static String extractBoundary(HttpHeaders headers) {
		return headers.entries()
		              .stream()
		              .filter(entry -> entry.getKey()
		                                    .equalsIgnoreCase("Content-Type"))
		              .map(entry -> MULTIPART_PATTERN.matcher(entry.getValue()))
		              .filter(Matcher::find)
		              .map(matcher -> matcher.group(1))
		              .findFirst()
		              .orElseThrow(() -> new IllegalStateException(
				              "Not a valid multipart response"));
	}

}