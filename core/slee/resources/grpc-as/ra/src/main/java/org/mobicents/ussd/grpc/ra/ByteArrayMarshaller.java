/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2017, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package org.mobicents.ussd.grpc.ra;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import io.grpc.MethodDescriptor;

/**
 * gRPC marshaller that passes the message body through as raw bytes. The body is a JSON envelope
 * (see {@code GrpcEnvelopeCodec}); using raw bytes keeps the RA free of protobuf code generation
 * while remaining a fully valid gRPC unary method.
 */
final class ByteArrayMarshaller implements MethodDescriptor.Marshaller<byte[]> {

    static final ByteArrayMarshaller INSTANCE = new ByteArrayMarshaller();

    private ByteArrayMarshaller() {
    }

    @Override
    public InputStream stream(byte[] value) {
        return new ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(InputStream stream) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            byte[] buf = new byte[4096];
            int read;
            while ((read = stream.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read gRPC message body", e);
        }
    }
}
