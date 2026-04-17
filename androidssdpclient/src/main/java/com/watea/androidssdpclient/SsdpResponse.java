/*
 * Copyright (c) 2024. Stephane Treuchot
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to
 * do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package com.watea.androidssdpclient;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public class SsdpResponse {
  private static final String LOG_TAG = SsdpResponse.class.getSimpleName();
  private static final String S_CRLF = "\r\n";
  private static final byte[] DOUBLE_CRLF = (S_CRLF + S_CRLF).getBytes(UTF_8);
  private static final Pattern CACHE_CONTROL_PATTERN = Pattern.compile("max-age *= *([0-9]+).*");
  // ThreadLocal because SimpleDateFormat is not thread-safe
  private static final ThreadLocal<SimpleDateFormat> DATE_HEADER_FORMAT =
    ThreadLocal.withInitial(() -> new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US));
  private static final Pattern SEARCH_REQUEST_LINE_PATTERN = Pattern.compile("^HTTP/1\\.1 [0-9]+ .*");
  private static final Pattern SERVICE_ANNOUNCEMENT_LINE_PATTERN = Pattern.compile("NOTIFY \\* HTTP/1\\.1");
  private static final Pattern HEADER_PATTERN = Pattern.compile("(.*?):(.*)$");
  private static final String CACHE_CONTROL = "CACHE-CONTROL";
  private static final String EXPIRES = "EXPIRES";

  @NonNull
  private final Map<String, String> headers;
  @Nullable
  private final byte[] body;
  @NonNull
  private final InetAddress originAddress;
  private final long expiry;
  @NonNull
  private final Type type;

  public SsdpResponse(
    @NonNull Type type,
    @NonNull Map<String, String> headers,
    @Nullable byte[] body,
    long expiry,
    @NonNull InetAddress originAddress) {
    this.type = type;
    this.headers = headers;
    this.body = body;
    this.expiry = expiry;
    this.originAddress = originAddress;
  }

  @NonNull
  public static SsdpResponse from(@NonNull DatagramPacket datagramPacket) throws IllegalArgumentException {
    final byte[] data = datagramPacket.getData();
    int endOfHeaders = findEndOfHeaders(data);
    if (endOfHeaders == -1) {
      endOfHeaders = datagramPacket.getLength();
    }
    final List<String> headerLines = Arrays.asList(new String(Arrays.copyOfRange(data, 0, endOfHeaders)).split(S_CRLF));
    final String firstLine = headerLines.get(0);
    final Type type =
      SEARCH_REQUEST_LINE_PATTERN.matcher(firstLine).matches() ?
        Type.DISCOVERY_RESPONSE :
        SERVICE_ANNOUNCEMENT_LINE_PATTERN.matcher(firstLine).matches() ?
          Type.PRESENCE_ANNOUNCEMENT :
          null;
    if (type == null) {
      throw new IllegalArgumentException("Failed to parse first line: " + firstLine);
    }
    final Map<String, String> headers = new HashMap<>();
    headerLines.stream().map(HEADER_PATTERN::matcher).filter(Matcher::matches).forEach(matcher -> {
      final String key = matcher.group(1);
      final String value = matcher.group(2);
      if ((key != null) && (value != null)) {
        headers.put(key.toUpperCase().trim(), value.trim());
      }
    });
    final long expiry = parseCacheHeader(headers);
    final int endOfBody = datagramPacket.getLength();
    final byte[] body = (endOfBody > endOfHeaders + 4) ? Arrays.copyOfRange(data, endOfHeaders + 4, endOfBody) : null;
    return new SsdpResponse(type, headers, body, expiry, datagramPacket.getAddress());
  }

  private static long parseCacheHeader(@NonNull Map<String, String> headers) {
    final String cacheControlHeader = headers.get(CACHE_CONTROL);
    if (cacheControlHeader != null) {
      final Matcher m = CACHE_CONTROL_PATTERN.matcher(cacheControlHeader);
      if (m.matches()) {
        final String time = m.group(1);
        if (time != null) {
          return new Date().getTime() + Long.parseLong(time) * 1000L;
        }
      }
    }
    final String expires = headers.get(EXPIRES);
    if (expires != null) {
      try {
        final SimpleDateFormat simpleDateFormat = DATE_HEADER_FORMAT.get();
        final Date date = (simpleDateFormat == null) ? null : simpleDateFormat.parse(expires);
        return (date == null) ? 0 : date.getTime();
      } catch (ParseException parseException) {
        Log.d(LOG_TAG, "parseCacheHeader: failed to parse expires header");
      }
    }
    return 0;
  }

  private static int findEndOfHeaders(@NonNull byte[] data) {
    for (int i = 0; i <= data.length - DOUBLE_CRLF.length; i++) {
      if (Arrays.equals(DOUBLE_CRLF, Arrays.copyOfRange(data, i, i + DOUBLE_CRLF.length))) {
        return i;
      }
    }
    return -1;
  }

  @NonNull
  public Type getType() {
    return type;
  }

  @Nullable
  public byte[] getBody() {
    return body;
  }

  @NonNull
  public Map<String, String> getHeaders() {
    return new HashMap<>(headers);
  }

  @NonNull
  public InetAddress getOriginAddress() {
    return originAddress;
  }

  public long getExpiry() {
    return expiry;
  }

  public boolean isExpired() {
    return (expiry > 0) && (new Date().getTime() > expiry);
  }

  @NonNull
  @Override
  public String toString() {
    return "SsdpResponse{" +
      ", headers=" + headers +
      ", body=" + Arrays.toString(body) +
      '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if ((o == null) || (getClass() != o.getClass())) return false;
    final SsdpResponse that = (SsdpResponse) o;
    return headers.equals(that.headers) && Arrays.equals(body, that.body);
  }

  @Override
  public int hashCode() {
    int result = headers.hashCode();
    result = 31 * result + Arrays.hashCode(body);
    return result;
  }

  public enum Type {
    DISCOVERY_RESPONSE, PRESENCE_ANNOUNCEMENT
  }
}