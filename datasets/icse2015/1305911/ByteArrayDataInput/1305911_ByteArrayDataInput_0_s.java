 package org.apache.lucene.store;
 
 /**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  *     http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
 
import java.io.IOException;

 import org.apache.lucene.util.BytesRef;
 
 /** 
  * DataInput backed by a byte array.
 * <b>WARNING:</b> This class omits most low-level checks,
 * so be sure to test heavily with assertions enabled.
  * @lucene.experimental 
  */
 public final class ByteArrayDataInput extends DataInput {
 
   private byte[] bytes;
 
   private int pos;
   private int limit;
 
   public ByteArrayDataInput(byte[] bytes) {
     reset(bytes);
   }
 
   public ByteArrayDataInput(byte[] bytes, int offset, int len) {
     reset(bytes, offset, len);
   }
 
   public ByteArrayDataInput() {
     reset(BytesRef.EMPTY_BYTES);
   }
 
   public void reset(byte[] bytes) {
     reset(bytes, 0, bytes.length);
   }
 
   public int getPosition() {
     return pos;
   }
   
   public void reset(byte[] bytes, int offset, int len) {
     this.bytes = bytes;
     pos = offset;
     limit = offset + len;
   }
 
   public boolean eof() {
     return pos == limit;
   }
 
   public void skipBytes(int count) {
     pos += count;
    assert pos <= limit;
   }
 
   @Override
   public short readShort() {
     return (short) (((bytes[pos++] & 0xFF) <<  8) |  (bytes[pos++] & 0xFF));
   }
  
   @Override
   public int readInt() {
    assert pos+4 <= limit;
     return ((bytes[pos++] & 0xFF) << 24) | ((bytes[pos++] & 0xFF) << 16)
       | ((bytes[pos++] & 0xFF) <<  8) |  (bytes[pos++] & 0xFF);
   }
  
   @Override
   public long readLong() {
    assert pos+8 <= limit;
     final int i1 = ((bytes[pos++] & 0xff) << 24) | ((bytes[pos++] & 0xff) << 16) |
       ((bytes[pos++] & 0xff) << 8) | (bytes[pos++] & 0xff);
     final int i2 = ((bytes[pos++] & 0xff) << 24) | ((bytes[pos++] & 0xff) << 16) |
       ((bytes[pos++] & 0xff) << 8) | (bytes[pos++] & 0xff);
     return (((long)i1) << 32) | (i2 & 0xFFFFFFFFL);
   }
 
   @Override
   public int readVInt() {
    assert checkBounds();
     byte b = bytes[pos++];
     int i = b & 0x7F;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     i |= (b & 0x7F) << 7;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     i |= (b & 0x7F) << 14;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     i |= (b & 0x7F) << 21;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     // Warning: the next ands use 0x0F / 0xF0 - beware copy/paste errors:
     i |= (b & 0x0F) << 28;
     if ((b & 0xF0) == 0) return i;
     throw new RuntimeException("Invalid vInt detected (too many bits)");
   }
  
   @Override
   public long readVLong() {
    assert checkBounds();
     byte b = bytes[pos++];
     long i = b & 0x7FL;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     i |= (b & 0x7FL) << 7;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     i |= (b & 0x7FL) << 14;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     i |= (b & 0x7FL) << 21;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     i |= (b & 0x7FL) << 28;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     i |= (b & 0x7FL) << 35;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     i |= (b & 0x7FL) << 42;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     i |= (b & 0x7FL) << 49;
     if ((b & 0x80) == 0) return i;
    assert checkBounds();
     b = bytes[pos++];
     i |= (b & 0x7FL) << 56;
     if ((b & 0x80) == 0) return i;
     throw new RuntimeException("Invalid vLong detected (negative values disallowed)");
   }
 
   // NOTE: AIOOBE not EOF if you read too much
   @Override
   public byte readByte() {
    assert checkBounds();
     return bytes[pos++];
   }
 
   // NOTE: AIOOBE not EOF if you read too much
   @Override
   public void readBytes(byte[] b, int offset, int len) {
    assert pos + len <= limit;
     System.arraycopy(bytes, pos, b, offset, len);
     pos += len;
   }

  private boolean checkBounds() {
    return pos < limit;
  }
 }