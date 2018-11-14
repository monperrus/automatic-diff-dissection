 package org.apache.lucene.index;
 
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
 import java.util.ArrayList;
 import java.util.Arrays;
 import java.util.Collection;
 import java.util.HashMap;
 import java.util.HashSet;
 
 import java.util.List;
 import java.util.Map;
 import java.util.Set;
 
 import org.apache.lucene.document.Document;
 import org.apache.lucene.document.FieldSelector;
 import org.apache.lucene.search.Similarity;
 import org.apache.lucene.store.BufferedIndexInput;
 import org.apache.lucene.store.Directory;
 import org.apache.lucene.store.IndexInput;
 import org.apache.lucene.store.IndexOutput;
 import org.apache.lucene.util.BitVector;
 import org.apache.lucene.util.Bits;
 import org.apache.lucene.util.CloseableThreadLocal;
import org.apache.lucene.util.cache.Cache;
import org.apache.lucene.util.cache.SimpleLRUCache;
 import org.apache.lucene.index.codecs.Codecs;
 import org.apache.lucene.index.codecs.Codec;
 import org.apache.lucene.index.codecs.preflex.PreFlexFields;
 import org.apache.lucene.index.codecs.preflex.SegmentTermDocs;
 import org.apache.lucene.index.codecs.preflex.SegmentTermPositions;
 import org.apache.lucene.index.codecs.FieldsProducer;
 
 /** @version $Id */
 /**
  * <p><b>NOTE:</b> This API is new and still experimental
  * (subject to change suddenly in the next release)</p>
  */
 public class SegmentReader extends IndexReader implements Cloneable {
   protected boolean readOnly;
 
   private SegmentInfo si;
   private int readBufferSize;
   boolean isPreFlex;
 
   CloseableThreadLocal<FieldsReader> fieldsReaderLocal = new FieldsReaderLocal();
   CloseableThreadLocal<TermVectorsReader> termVectorsLocal = new CloseableThreadLocal<TermVectorsReader>();
 
   BitVector deletedDocs = null;
   Ref deletedDocsRef = null;
   private boolean deletedDocsDirty = false;
   private boolean normsDirty = false;
   private int pendingDeleteCount;
 
   private boolean rollbackHasChanges = false;
   private boolean rollbackDeletedDocsDirty = false;
   private boolean rollbackNormsDirty = false;
   private int rollbackPendingDeleteCount;
 
   // optionally used for the .nrm file shared by multiple norms
   private IndexInput singleNormStream;
   private Ref singleNormRef;
 
   CoreReaders core;
 
   // Holds core readers that are shared (unchanged) when
   // SegmentReader is cloned or reopened
   static final class CoreReaders {
 
     // Counts how many other reader share the core objects
     // (freqStream, proxStream, tis, etc.) of this reader;
     // when coreRef drops to 0, these core objects may be
     // closed.  A given instance of SegmentReader may be
     // closed, even those it shares core objects with other
     // SegmentReaders:
     private final Ref ref = new Ref();
 
     final String segment;
     final FieldInfos fieldInfos;
 
     final FieldsProducer fields;
     final boolean isPreFlex;
     final Codecs codecs;
     
     final Directory dir;
     final Directory cfsDir;
     final int readBufferSize;
     final int termsIndexDivisor;
 
     FieldsReader fieldsReaderOrig;
     TermVectorsReader termVectorsReaderOrig;
     CompoundFileReader cfsReader;
     CompoundFileReader storeCFSReader;
 
     CoreReaders(Directory dir, SegmentInfo si, int readBufferSize, int termsIndexDivisor, Codecs codecs) throws IOException {
 
       if (termsIndexDivisor < 1 && termsIndexDivisor != -1) {
         throw new IllegalArgumentException("indexDivisor must be -1 (don't load terms index) or greater than 0: got " + termsIndexDivisor);
       }
 
       segment = si.name;
       if (Codec.DEBUG) {
         System.out.println("sr: init core for segment=" + segment);
       }
       if (codecs == null) {
         codecs = Codecs.getDefault();
       }
       this.codecs = codecs;      
       this.readBufferSize = readBufferSize;
       this.dir = dir;
 
       boolean success = false;
 
       try {
         Directory dir0 = dir;
         if (si.getUseCompoundFile()) {
           cfsReader = new CompoundFileReader(dir, segment + "." + IndexFileNames.COMPOUND_FILE_EXTENSION, readBufferSize);
           dir0 = cfsReader;
         }
         cfsDir = dir0;
 
         fieldInfos = new FieldInfos(cfsDir, segment + "." + IndexFileNames.FIELD_INFOS_EXTENSION);
 
         this.termsIndexDivisor = termsIndexDivisor;
 
         // Ask codec for its Fields
         if (Codec.DEBUG) {
           System.out.println("sr.core.init: seg=" + si.name + " codec=" + si.getCodec());
         }
         fields = si.getCodec().fieldsProducer(cfsDir, fieldInfos, si, readBufferSize, termsIndexDivisor);
         assert fields != null;
 
         isPreFlex = fields instanceof PreFlexFields;
         success = true;
       } finally {
         if (!success) {
           decRef();
         }
       }
     }
 
     synchronized TermVectorsReader getTermVectorsReaderOrig() {
       return termVectorsReaderOrig;
     }
 
     synchronized FieldsReader getFieldsReaderOrig() {
       return fieldsReaderOrig;
     }
 
     synchronized void incRef() {
       ref.incRef();
     }
 
     synchronized Directory getCFSReader() {
       return cfsReader;
     }
 
     synchronized void decRef() throws IOException {
 
       if (ref.decRef() == 0) {
 
         if (fields != null) {
           fields.close();
         }
 
         if (termVectorsReaderOrig != null) {
           termVectorsReaderOrig.close();
         }
   
         if (fieldsReaderOrig != null) {
           fieldsReaderOrig.close();
         }
   
         if (cfsReader != null) {
           cfsReader.close();
         }
   
         if (storeCFSReader != null) {
           storeCFSReader.close();
         }
       }
     }
 
     synchronized void openDocStores(SegmentInfo si) throws IOException {
 
       assert si.name.equals(segment);
 
       if (fieldsReaderOrig == null) {
         final Directory storeDir;
         if (si.getDocStoreOffset() != -1) {
           if (si.getDocStoreIsCompoundFile()) {
             assert storeCFSReader == null;
             storeCFSReader = new CompoundFileReader(dir,
                                                     si.getDocStoreSegment() + "." + IndexFileNames.COMPOUND_FILE_STORE_EXTENSION,
                                                     readBufferSize);
             storeDir = storeCFSReader;
             assert storeDir != null;
           } else {
             storeDir = dir;
             assert storeDir != null;
           }
         } else if (si.getUseCompoundFile()) {
           // In some cases, we were originally opened when CFS
           // was not used, but then we are asked to open doc
           // stores after the segment has switched to CFS
           if (cfsReader == null) {
             cfsReader = new CompoundFileReader(dir, segment + "." + IndexFileNames.COMPOUND_FILE_EXTENSION, readBufferSize);
           }
           storeDir = cfsReader;
           assert storeDir != null;
         } else {
           storeDir = dir;
           assert storeDir != null;
         }
 
         final String storesSegment;
         if (si.getDocStoreOffset() != -1) {
           storesSegment = si.getDocStoreSegment();
         } else {
           storesSegment = segment;
         }
 
         fieldsReaderOrig = new FieldsReader(storeDir, storesSegment, fieldInfos, readBufferSize,
                                             si.getDocStoreOffset(), si.docCount);
 
         // Verify two sources of "maxDoc" agree:
         if (si.getDocStoreOffset() == -1 && fieldsReaderOrig.size() != si.docCount) {
           throw new CorruptIndexException("doc counts differ for segment " + segment + ": fieldsReader shows " + fieldsReaderOrig.size() + " but segmentInfo shows " + si.docCount);
         }
 
         if (fieldInfos.hasVectors()) { // open term vector files only as needed
           termVectorsReaderOrig = new TermVectorsReader(storeDir, storesSegment, fieldInfos, readBufferSize, si.getDocStoreOffset(), si.docCount);
         }
       }
     }
   }
 
   /**
    * Sets the initial value 
    */
   private class FieldsReaderLocal extends CloseableThreadLocal<FieldsReader> {
     @Override
     protected FieldsReader initialValue() {
       return (FieldsReader) core.getFieldsReaderOrig().clone();
     }
   }
   
   static class Ref {
     private int refCount = 1;
     
     @Override
     public String toString() {
       return "refcount: "+refCount;
     }
     
     public synchronized int refCount() {
       return refCount;
     }
     
     public synchronized int incRef() {
       assert refCount > 0;
       refCount++;
       return refCount;
     }
 
     public synchronized int decRef() {
       assert refCount > 0;
       refCount--;
       return refCount;
     }
   }
   
   /**
    * Byte[] referencing is used because a new norm object needs 
    * to be created for each clone, and the byte array is all 
    * that is needed for sharing between cloned readers.  The 
    * current norm referencing is for sharing between readers 
    * whereas the byte[] referencing is for copy on write which 
    * is independent of reader references (i.e. incRef, decRef).
    */
 
   final class Norm implements Cloneable {
     private int refCount = 1;
 
     // If this instance is a clone, the originalNorm
     // references the Norm that has a real open IndexInput:
     private Norm origNorm;
 
     private IndexInput in;
     private long normSeek;
 
     // null until bytes is set
     private Ref bytesRef;
     private byte[] bytes;
     private boolean dirty;
     private int number;
     private boolean rollbackDirty;
     
     public Norm(IndexInput in, int number, long normSeek) {
       this.in = in;
       this.number = number;
       this.normSeek = normSeek;
     }
 
     public synchronized void incRef() {
       assert refCount > 0 && (origNorm == null || origNorm.refCount > 0);
       refCount++;
     }
 
     private void closeInput() throws IOException {
       if (in != null) {
         if (in != singleNormStream) {
           // It's private to us -- just close it
           in.close();
         } else {
           // We are sharing this with others -- decRef and
           // maybe close the shared norm stream
           if (singleNormRef.decRef() == 0) {
             singleNormStream.close();
             singleNormStream = null;
           }
         }
 
         in = null;
       }
     }
 
     public synchronized void decRef() throws IOException {
       assert refCount > 0 && (origNorm == null || origNorm.refCount > 0);
 
       if (--refCount == 0) {
         if (origNorm != null) {
           origNorm.decRef();
           origNorm = null;
         } else {
           closeInput();
         }
 
         if (bytes != null) {
           assert bytesRef != null;
           bytesRef.decRef();
           bytes = null;
           bytesRef = null;
         } else {
           assert bytesRef == null;
         }
       }
     }
 
     // Load bytes but do not cache them if they were not
     // already cached
     public synchronized void bytes(byte[] bytesOut, int offset, int len) throws IOException {
       assert refCount > 0 && (origNorm == null || origNorm.refCount > 0);
       if (bytes != null) {
         // Already cached -- copy from cache:
         assert len <= maxDoc();
         System.arraycopy(bytes, 0, bytesOut, offset, len);
       } else {
         // Not cached
         if (origNorm != null) {
           // Ask origNorm to load
           origNorm.bytes(bytesOut, offset, len);
         } else {
           // We are orig -- read ourselves from disk:
           synchronized(in) {
             in.seek(normSeek);
             in.readBytes(bytesOut, offset, len, false);
           }
         }
       }
     }
 
     // Load & cache full bytes array.  Returns bytes.
     public synchronized byte[] bytes() throws IOException {
       assert refCount > 0 && (origNorm == null || origNorm.refCount > 0);
       if (bytes == null) {                     // value not yet read
         assert bytesRef == null;
         if (origNorm != null) {
           // Ask origNorm to load so that for a series of
           // reopened readers we share a single read-only
           // byte[]
           bytes = origNorm.bytes();
           bytesRef = origNorm.bytesRef;
           bytesRef.incRef();
 
           // Once we've loaded the bytes we no longer need
           // origNorm:
           origNorm.decRef();
           origNorm = null;
 
         } else {
           // We are the origNorm, so load the bytes for real
           // ourself:
           final int count = maxDoc();
           bytes = new byte[count];
 
           // Since we are orig, in must not be null
           assert in != null;
 
           // Read from disk.
           synchronized(in) {
             in.seek(normSeek);
             in.readBytes(bytes, 0, count, false);
           }
 
           bytesRef = new Ref();
           closeInput();
         }
       }
 
       return bytes;
     }
 
     // Only for testing
     Ref bytesRef() {
       return bytesRef;
     }
 
     // Called if we intend to change a norm value.  We make a
     // private copy of bytes if it's shared with others:
     public synchronized byte[] copyOnWrite() throws IOException {
       assert refCount > 0 && (origNorm == null || origNorm.refCount > 0);
       bytes();
       assert bytes != null;
       assert bytesRef != null;
       if (bytesRef.refCount() > 1) {
         // I cannot be the origNorm for another norm
         // instance if I'm being changed.  Ie, only the
         // "head Norm" can be changed:
         assert refCount == 1;
         final Ref oldRef = bytesRef;
         bytes = cloneNormBytes(bytes);
         bytesRef = new Ref();
         oldRef.decRef();
       }
       dirty = true;
       return bytes;
     }
     
     // Returns a copy of this Norm instance that shares
     // IndexInput & bytes with the original one
     @Override
     public synchronized Object clone() {
       assert refCount > 0 && (origNorm == null || origNorm.refCount > 0);
         
       Norm clone;
       try {
         clone = (Norm) super.clone();
       } catch (CloneNotSupportedException cnse) {
         // Cannot happen
         throw new RuntimeException("unexpected CloneNotSupportedException", cnse);
       }
       clone.refCount = 1;
 
       if (bytes != null) {
         assert bytesRef != null;
         assert origNorm == null;
 
         // Clone holds a reference to my bytes:
         clone.bytesRef.incRef();
       } else {
         assert bytesRef == null;
         if (origNorm == null) {
           // I become the origNorm for the clone:
           clone.origNorm = this;
         }
         clone.origNorm.incRef();
       }
 
       // Only the origNorm will actually readBytes from in:
       clone.in = null;
 
       return clone;
     }
 
     // Flush all pending changes to the next generation
     // separate norms file.
     public void reWrite(SegmentInfo si) throws IOException {
       assert refCount > 0 && (origNorm == null || origNorm.refCount > 0): "refCount=" + refCount + " origNorm=" + origNorm;
 
       // NOTE: norms are re-written in regular directory, not cfs
       si.advanceNormGen(this.number);
       IndexOutput out = directory().createOutput(si.getNormFileName(this.number));
       try {
         out.writeBytes(bytes, maxDoc());
       } finally {
         out.close();
       }
       this.dirty = false;
     }
   }
 
   Map<String,Norm> norms = new HashMap<String,Norm>();
   
   /**
    * @throws CorruptIndexException if the index is corrupt
    * @throws IOException if there is a low-level IO error
    */
   public static SegmentReader get(boolean readOnly, SegmentInfo si, int termInfosIndexDivisor) throws CorruptIndexException, IOException {
     return get(readOnly, si.dir, si, BufferedIndexInput.BUFFER_SIZE, true, termInfosIndexDivisor, null);
   }
 
   /**
    * @throws CorruptIndexException if the index is corrupt
    * @throws IOException if there is a low-level IO error
    */
   public static SegmentReader get(boolean readOnly,
                                   Directory dir,
                                   SegmentInfo si,
                                   int readBufferSize,
                                   boolean doOpenStores,
                                   int termInfosIndexDivisor,
                                   Codecs codecs)
     throws CorruptIndexException, IOException {
     if (codecs == null)  {
       codecs = Codecs.getDefault();
     }
     
     SegmentReader instance = readOnly ? new ReadOnlySegmentReader() : new SegmentReader();
     instance.readOnly = readOnly;
     instance.si = si;
     instance.readBufferSize = readBufferSize;
 
     boolean success = false;
 
     try {
       instance.core = new CoreReaders(dir, si, readBufferSize, termInfosIndexDivisor, codecs);
       if (doOpenStores) {
         instance.core.openDocStores(si);
       }
       instance.loadDeletedDocs();
       instance.openNorms(instance.core.cfsDir, readBufferSize);
       success = true;
     } finally {
 
       // With lock-less commits, it's entirely possible (and
       // fine) to hit a FileNotFound exception above.  In
       // this case, we want to explicitly close any subset
       // of things that were opened so that we don't have to
       // wait for a GC to do so.
       if (!success) {
         instance.doClose();
       }
     }
     return instance;
   }
 
   void openDocStores() throws IOException {
     core.openDocStores(si);
   }
 
   @Override
   public synchronized Bits getDeletedDocs() {
     return deletedDocs;
   }
 
   private void loadDeletedDocs() throws IOException {
     // NOTE: the bitvector is stored using the regular directory, not cfs
     if (hasDeletions(si)) {
       deletedDocs = new BitVector(directory(), si.getDelFileName());
       deletedDocsRef = new Ref();
      
       assert si.getDelCount() == deletedDocs.count() : 
         "delete count mismatch: info=" + si.getDelCount() + " vs BitVector=" + deletedDocs.count();
 
       // Verify # deletes does not exceed maxDoc for this
       // segment:
       assert si.getDelCount() <= maxDoc() : 
         "delete count mismatch: " + deletedDocs.count() + ") exceeds max doc (" + maxDoc() + ") for segment " + si.name;
 
     } else
       assert si.getDelCount() == 0;
   }
   
   /**
    * Clones the norm bytes.  May be overridden by subclasses.  New and experimental.
    * @param bytes Byte array to clone
    * @return New BitVector
    */
   protected byte[] cloneNormBytes(byte[] bytes) {
     byte[] cloneBytes = new byte[bytes.length];
     System.arraycopy(bytes, 0, cloneBytes, 0, bytes.length);
     return cloneBytes;
   }
   
   /**
    * Clones the deleteDocs BitVector.  May be overridden by subclasses. New and experimental.
    * @param bv BitVector to clone
    * @return New BitVector
    */
   protected BitVector cloneDeletedDocs(BitVector bv) {
     return (BitVector)bv.clone();
   }
 
   @Override
   public final synchronized Object clone() {
     try {
       return clone(readOnly); // Preserve current readOnly
     } catch (Exception ex) {
       throw new RuntimeException(ex);
     }
   }
 
   @Override
   public final synchronized IndexReader clone(boolean openReadOnly) throws CorruptIndexException, IOException {
     return reopenSegment(si, true, openReadOnly);
   }
 
   synchronized SegmentReader reopenSegment(SegmentInfo si, boolean doClone, boolean openReadOnly) throws CorruptIndexException, IOException {
     boolean deletionsUpToDate = (this.si.hasDeletions() == si.hasDeletions()) 
                                   && (!si.hasDeletions() || this.si.getDelFileName().equals(si.getDelFileName()));
     boolean normsUpToDate = true;
     
     boolean[] fieldNormsChanged = new boolean[core.fieldInfos.size()];
     final int fieldCount = core.fieldInfos.size();
     for (int i = 0; i < fieldCount; i++) {
       if (!this.si.getNormFileName(i).equals(si.getNormFileName(i))) {
         normsUpToDate = false;
         fieldNormsChanged[i] = true;
       }
     }
 
     // if we're cloning we need to run through the reopenSegment logic
     // also if both old and new readers aren't readonly, we clone to avoid sharing modifications
     if (normsUpToDate && deletionsUpToDate && !doClone && openReadOnly && readOnly) {
       return this;
     }    
 
     // When cloning, the incoming SegmentInfos should not
     // have any changes in it:
     assert !doClone || (normsUpToDate && deletionsUpToDate);
 
     // clone reader
     SegmentReader clone = openReadOnly ? new ReadOnlySegmentReader() : new SegmentReader();
 
     boolean success = false;
     try {
       core.incRef();
       clone.core = core;
       clone.readOnly = openReadOnly;
       clone.si = si;
       clone.readBufferSize = readBufferSize;
 
       if (!openReadOnly && hasChanges) {
         // My pending changes transfer to the new reader
         clone.pendingDeleteCount = pendingDeleteCount;
         clone.deletedDocsDirty = deletedDocsDirty;
         clone.normsDirty = normsDirty;
         clone.hasChanges = hasChanges;
         hasChanges = false;
       }
       
       if (doClone) {
         if (deletedDocs != null) {
           deletedDocsRef.incRef();
           clone.deletedDocs = deletedDocs;
           clone.deletedDocsRef = deletedDocsRef;
         }
       } else {
         if (!deletionsUpToDate) {
           // load deleted docs
           assert clone.deletedDocs == null;
           clone.loadDeletedDocs();
         } else if (deletedDocs != null) {
           deletedDocsRef.incRef();
           clone.deletedDocs = deletedDocs;
           clone.deletedDocsRef = deletedDocsRef;
         }
       }
 
       clone.norms = new HashMap<String,Norm>();
 
       // Clone norms
       for (int i = 0; i < fieldNormsChanged.length; i++) {
 
         // Clone unchanged norms to the cloned reader
         if (doClone || !fieldNormsChanged[i]) {
           final String curField = core.fieldInfos.fieldInfo(i).name;
           Norm norm = this.norms.get(curField);
           if (norm != null)
             clone.norms.put(curField, (Norm) norm.clone());
         }
       }
 
       // If we are not cloning, then this will open anew
       // any norms that have changed:
       clone.openNorms(si.getUseCompoundFile() ? core.getCFSReader() : directory(), readBufferSize);
 
       success = true;
     } finally {
       if (!success) {
         // An exception occurred during reopen, we have to decRef the norms
         // that we incRef'ed already and close singleNormsStream and FieldsReader
         clone.decRef();
       }
     }
     
     return clone;
   }
 
   @Override
   protected void doCommit(Map<String,String> commitUserData) throws IOException {
     if (hasChanges) {
       if (deletedDocsDirty) {               // re-write deleted
         si.advanceDelGen();
 
         // We can write directly to the actual name (vs to a
         // .tmp & renaming it) because the file is not live
         // until segments file is written:
         deletedDocs.write(directory(), si.getDelFileName());
 
         si.setDelCount(si.getDelCount()+pendingDeleteCount);
         pendingDeleteCount = 0;
         assert deletedDocs.count() == si.getDelCount(): "delete count mismatch during commit: info=" + si.getDelCount() + " vs BitVector=" + deletedDocs.count();
       } else {
         assert pendingDeleteCount == 0;
       }
 
       if (normsDirty) {               // re-write norms
         si.setNumFields(core.fieldInfos.size());
         for (final Norm norm : norms.values()) {
           if (norm.dirty) {
             norm.reWrite(si);
           }
         }
       }
       deletedDocsDirty = false;
       normsDirty = false;
       hasChanges = false;
     }
   }
 
   FieldsReader getFieldsReader() {
     return fieldsReaderLocal.get();
   }
 
   @Override
   protected void doClose() throws IOException {
     termVectorsLocal.close();
     fieldsReaderLocal.close();
     
     if (deletedDocs != null) {
       deletedDocsRef.decRef();
       // null so if an app hangs on to us we still free most ram
       deletedDocs = null;
     }
 
     for (final Norm norm : norms.values()) {
       norm.decRef();
     }
     if (core != null) {
       core.decRef();
     }
   }
 
   static boolean hasDeletions(SegmentInfo si) throws IOException {
     // Don't call ensureOpen() here (it could affect performance)
     return si.hasDeletions();
   }
 
   @Override
   public boolean hasDeletions() {
     // Don't call ensureOpen() here (it could affect performance)
     return deletedDocs != null;
   }
 
   static boolean usesCompoundFile(SegmentInfo si) throws IOException {
     return si.getUseCompoundFile();
   }
 
   static boolean hasSeparateNorms(SegmentInfo si) throws IOException {
     return si.hasSeparateNorms();
   }
 
   @Override
   protected void doDelete(int docNum) {
     if (deletedDocs == null) {
       deletedDocs = new BitVector(maxDoc());
       deletedDocsRef = new Ref();
     }
     // there is more than 1 SegmentReader with a reference to this
     // deletedDocs BitVector so decRef the current deletedDocsRef,
     // clone the BitVector, create a new deletedDocsRef
     if (deletedDocsRef.refCount() > 1) {
       Ref oldRef = deletedDocsRef;
       deletedDocs = cloneDeletedDocs(deletedDocs);
       deletedDocsRef = new Ref();
       oldRef.decRef();
     }
     deletedDocsDirty = true;
     if (!deletedDocs.getAndSet(docNum))
       pendingDeleteCount++;
   }
 
   @Override
   protected void doUndeleteAll() {
     deletedDocsDirty = false;
     if (deletedDocs != null) {
       assert deletedDocsRef != null;
       deletedDocsRef.decRef();
       deletedDocs = null;
       deletedDocsRef = null;
       pendingDeleteCount = 0;
       si.clearDelGen();
       si.setDelCount(0);
     } else {
       assert deletedDocsRef == null;
       assert pendingDeleteCount == 0;
     }
   }
 
   List<String> files() throws IOException {
     return new ArrayList<String>(si.files());
   }
   
   @Override
   public TermEnum terms() throws IOException {
     ensureOpen();
     if (isPreFlex) {
       // For old API on an old segment, instead of
       // converting old API -> new API -> old API, just give
       // direct access to old:
       return ((PreFlexFields) core.fields).tis.terms();
     } else {
       // Emulate old API on top of new index
       return new LegacyTermEnum(null);
     }
   }
 
   /** @deprecated Please switch to the flex API ({@link
    * #fields}) instead. */
   @Override
   public TermEnum terms(Term t) throws IOException {
     ensureOpen();
     if (isPreFlex) {
       // For old API on an old segment, instead of
       // converting old API -> new API -> old API, just give
       // direct access to old:
       return ((PreFlexFields) core.fields).tis.terms(t);
     } else {
       // Emulate old API on top of new index
       return new LegacyTermEnum(t);
     }
   }
 
   FieldInfos fieldInfos() {
     return core.fieldInfos;
   }
 
   @Override
   public Document document(int n, FieldSelector fieldSelector) throws CorruptIndexException, IOException {
     ensureOpen();
     return getFieldsReader().doc(n, fieldSelector);
   }
 
   @Override
   public synchronized boolean isDeleted(int n) {
     return (deletedDocs != null && deletedDocs.get(n));
   }
 
   /** @deprecated Switch to the flex API ({@link
    * IndexReader#termDocsEnum}) instead. */
   @Override
   public TermDocs termDocs(Term term) throws IOException {
     if (term == null) {
       return new AllTermDocs(this);
     } else {
       return super.termDocs(term);
     }
   }
   
   @Override
   public Fields fields() throws IOException {
     return core.fields;
   }
 
   /** @deprecated Switch to the flex API {@link
    *  IndexReader#termDocsEnum} instead. */
   @Override
   public TermDocs termDocs() throws IOException {
     ensureOpen();
     if (isPreFlex) {
       // For old API on an old segment, instead of
       // converting old API -> new API -> old API, just give
       // direct access to old:
       final PreFlexFields pre = (PreFlexFields) core.fields;
       return new SegmentTermDocs(pre.freqStream, deletedDocs, pre.tis, core.fieldInfos);
     } else {
       // Emulate old API
       return new LegacyTermDocs();
     }
   }
 
   /** @deprecated Switch to the flex API {@link
    *  IndexReader#termDocsEnum} instead */
   @Override
   public TermPositions termPositions() throws IOException {
     ensureOpen();
     if (isPreFlex) {
       // For old API on an old segment, instead of
       // converting old API -> new API -> old API, just give
       // direct access to old:
       final PreFlexFields pre = (PreFlexFields) core.fields;
       return new SegmentTermPositions(pre.freqStream, pre.proxStream, deletedDocs, pre.tis, core.fieldInfos);
     } else
       // Emulate old API
       return new LegacyTermPositions();
   }
 
  private final CloseableThreadLocal perThread = new CloseableThreadLocal();

  // nocommit -- move term vectors under here
  private static final class PerThread {
    LegacyTermEnum terms;
    
    // Used for caching the least recently looked-up Terms
    Cache termsCache;
  }

  private final static int DEFAULT_TERMS_CACHE_SIZE = 1024;

  private PerThread getPerThread() throws IOException {
    PerThread resources = (PerThread) perThread.get();
    if (resources == null) {
      resources = new PerThread();
      resources.terms = new LegacyTermEnum(null);
      // Cache does not have to be thread-safe, it is only used by one thread at the same time
      resources.termsCache = new SimpleLRUCache(DEFAULT_TERMS_CACHE_SIZE);
      perThread.set(resources);
    }
    return resources;
  }

  
   @Override
   public int docFreq(Term t) throws IOException {
     ensureOpen();
     Terms terms = core.fields.terms(t.field);
     if (terms != null) {
       return terms.docFreq(new TermRef(t.text));
     } else {
       return 0;
     }
   }
 
   @Override
   public int docFreq(String field, TermRef term) throws IOException {
     ensureOpen();
 
     Terms terms = core.fields.terms(field);
     if (terms != null) {
       return terms.docFreq(term);
     } else {
       return 0;
     }
   }
 
   @Override
   public int numDocs() {
     // Don't call ensureOpen() here (it could affect performance)
     int n = maxDoc();
     if (deletedDocs != null)
       n -= deletedDocs.count();
     return n;
   }
 
   @Override
   public int maxDoc() {
     // Don't call ensureOpen() here (it could affect performance)
     return si.docCount;
   }
 
   /**
    * @see IndexReader#getFieldNames(IndexReader.FieldOption fldOption)
    */
   @Override
   public Collection<String> getFieldNames(IndexReader.FieldOption fieldOption) {
     ensureOpen();
 
     Set<String> fieldSet = new HashSet<String>();
     for (int i = 0; i < core.fieldInfos.size(); i++) {
       FieldInfo fi = core.fieldInfos.fieldInfo(i);
       if (fieldOption == IndexReader.FieldOption.ALL) {
         fieldSet.add(fi.name);
       }
       else if (!fi.isIndexed && fieldOption == IndexReader.FieldOption.UNINDEXED) {
         fieldSet.add(fi.name);
       }
       else if (fi.omitTermFreqAndPositions && fieldOption == IndexReader.FieldOption.OMIT_TERM_FREQ_AND_POSITIONS) {
         fieldSet.add(fi.name);
       }
       else if (fi.storePayloads && fieldOption == IndexReader.FieldOption.STORES_PAYLOADS) {
         fieldSet.add(fi.name);
       }
       else if (fi.isIndexed && fieldOption == IndexReader.FieldOption.INDEXED) {
         fieldSet.add(fi.name);
       }
       else if (fi.isIndexed && fi.storeTermVector == false && fieldOption == IndexReader.FieldOption.INDEXED_NO_TERMVECTOR) {
         fieldSet.add(fi.name);
       }
       else if (fi.storeTermVector == true &&
                fi.storePositionWithTermVector == false &&
                fi.storeOffsetWithTermVector == false &&
                fieldOption == IndexReader.FieldOption.TERMVECTOR) {
         fieldSet.add(fi.name);
       }
       else if (fi.isIndexed && fi.storeTermVector && fieldOption == IndexReader.FieldOption.INDEXED_WITH_TERMVECTOR) {
         fieldSet.add(fi.name);
       }
       else if (fi.storePositionWithTermVector && fi.storeOffsetWithTermVector == false && fieldOption == IndexReader.FieldOption.TERMVECTOR_WITH_POSITION) {
         fieldSet.add(fi.name);
       }
       else if (fi.storeOffsetWithTermVector && fi.storePositionWithTermVector == false && fieldOption == IndexReader.FieldOption.TERMVECTOR_WITH_OFFSET) {
         fieldSet.add(fi.name);
       }
       else if ((fi.storeOffsetWithTermVector && fi.storePositionWithTermVector) &&
                 fieldOption == IndexReader.FieldOption.TERMVECTOR_WITH_POSITION_OFFSET) {
         fieldSet.add(fi.name);
       }
     }
     return fieldSet;
   }
 
 
   @Override
   public synchronized boolean hasNorms(String field) {
     ensureOpen();
     return norms.containsKey(field);
   }
 
   // can return null if norms aren't stored
   protected synchronized byte[] getNorms(String field) throws IOException {
     Norm norm = norms.get(field);
     if (norm == null) return null;  // not indexed, or norms not stored
     return norm.bytes();
   }
 
   // returns fake norms if norms aren't available
   @Override
   public synchronized byte[] norms(String field) throws IOException {
     ensureOpen();
     byte[] bytes = getNorms(field);
     return bytes;
   }
 
   @Override
   protected void doSetNorm(int doc, String field, byte value)
           throws IOException {
     Norm norm = norms.get(field);
     if (norm == null)                             // not an indexed field
       return;
 
     normsDirty = true;
     norm.copyOnWrite()[doc] = value;                    // set the value
   }
 
   /** Read norms into a pre-allocated array. */
   @Override
   public synchronized void norms(String field, byte[] bytes, int offset)
     throws IOException {
 
     ensureOpen();
     Norm norm = norms.get(field);
     if (norm == null) {
       Arrays.fill(bytes, offset, bytes.length, Similarity.getDefault().encodeNormValue(1.0f));
       return;
     }
   
     norm.bytes(bytes, offset, maxDoc());
   }
 
 
   private void openNorms(Directory cfsDir, int readBufferSize) throws IOException {
     long nextNormSeek = SegmentMerger.NORMS_HEADER.length; //skip header (header unused for now)
     int maxDoc = maxDoc();
     for (int i = 0; i < core.fieldInfos.size(); i++) {
       FieldInfo fi = core.fieldInfos.fieldInfo(i);
       if (norms.containsKey(fi.name)) {
         // in case this SegmentReader is being re-opened, we might be able to
         // reuse some norm instances and skip loading them here
         continue;
       }
       if (fi.isIndexed && !fi.omitNorms) {
         Directory d = directory();
         String fileName = si.getNormFileName(fi.number);
         if (!si.hasSeparateNorms(fi.number)) {
           d = cfsDir;
         }
         
         // singleNormFile means multiple norms share this file
         boolean singleNormFile = fileName.endsWith("." + IndexFileNames.NORMS_EXTENSION);
         IndexInput normInput = null;
         long normSeek;
 
         if (singleNormFile) {
           normSeek = nextNormSeek;
           if (singleNormStream == null) {
             singleNormStream = d.openInput(fileName, readBufferSize);
             singleNormRef = new Ref();
           } else {
             singleNormRef.incRef();
           }
           // All norms in the .nrm file can share a single IndexInput since
           // they are only used in a synchronized context.
           // If this were to change in the future, a clone could be done here.
           normInput = singleNormStream;
         } else {
           normSeek = 0;
           normInput = d.openInput(fileName);
         }
 
         norms.put(fi.name, new Norm(normInput, fi.number, normSeek));
         nextNormSeek += maxDoc; // increment also if some norms are separate
       }
     }
   }
 
   // NOTE: only called from IndexWriter when a near
   // real-time reader is opened, or applyDeletes is run,
   // sharing a segment that's still being merged.  This
   // method is not thread safe, and relies on the
   // synchronization in IndexWriter
   void loadTermsIndex(int indexDivisor) throws IOException {
     core.fields.loadTermsIndex(indexDivisor);
   }
 
   // for testing only
   boolean normsClosed() {
     if (singleNormStream != null) {
       return false;
     }
     for (final Norm norm : norms.values()) {
       if (norm.refCount > 0) {
         return false;
       }
     }
     return true;
   }
 
   // for testing only
   boolean normsClosed(String field) {
     return norms.get(field).refCount == 0;
   }
 
   /**
    * Create a clone from the initial TermVectorsReader and store it in the ThreadLocal.
    * @return TermVectorsReader
    */
   TermVectorsReader getTermVectorsReader() {
     TermVectorsReader tvReader = termVectorsLocal.get();
     if (tvReader == null) {
       TermVectorsReader orig = core.getTermVectorsReaderOrig();
       if (orig == null) {
         return null;
       } else {
         try {
           tvReader = (TermVectorsReader) orig.clone();
         } catch (CloneNotSupportedException cnse) {
           return null;
         }
       }
       termVectorsLocal.set(tvReader);
     }
     return tvReader;
   }
 
   TermVectorsReader getTermVectorsReaderOrig() {
     return core.getTermVectorsReaderOrig();
   }
   
   /** Return a term frequency vector for the specified document and field. The
    *  vector returned contains term numbers and frequencies for all terms in
    *  the specified field of this document, if the field had storeTermVector
    *  flag set.  If the flag was not set, the method returns null.
    * @throws IOException
    */
   @Override
   public TermFreqVector getTermFreqVector(int docNumber, String field) throws IOException {
     // Check if this field is invalid or has no stored term vector
     ensureOpen();
     FieldInfo fi = core.fieldInfos.fieldInfo(field);
     if (fi == null || !fi.storeTermVector) 
       return null;
     
     TermVectorsReader termVectorsReader = getTermVectorsReader();
     if (termVectorsReader == null)
       return null;
     
     return termVectorsReader.get(docNumber, field);
   }
 
 
   @Override
   public void getTermFreqVector(int docNumber, String field, TermVectorMapper mapper) throws IOException {
     ensureOpen();
     FieldInfo fi = core.fieldInfos.fieldInfo(field);
     if (fi == null || !fi.storeTermVector)
       return;
 
     TermVectorsReader termVectorsReader = getTermVectorsReader();
     if (termVectorsReader == null) {
       return;
     }
 
 
     termVectorsReader.get(docNumber, field, mapper);
   }
 
 
   @Override
   public void getTermFreqVector(int docNumber, TermVectorMapper mapper) throws IOException {
     ensureOpen();
 
     TermVectorsReader termVectorsReader = getTermVectorsReader();
     if (termVectorsReader == null)
       return;
 
     termVectorsReader.get(docNumber, mapper);
   }
 
   /** Return an array of term frequency vectors for the specified document.
    *  The array contains a vector for each vectorized field in the document.
    *  Each vector vector contains term numbers and frequencies for all terms
    *  in a given vectorized field.
    *  If no such fields existed, the method returns null.
    * @throws IOException
    */
   @Override
   public TermFreqVector[] getTermFreqVectors(int docNumber) throws IOException {
     ensureOpen();
     
     TermVectorsReader termVectorsReader = getTermVectorsReader();
     if (termVectorsReader == null)
       return null;
     
     return termVectorsReader.get(docNumber);
   }
   
   /**
    * Return the name of the segment this reader is reading.
    */
   public String getSegmentName() {
     return core.segment;
   }
   
   /**
    * Return the SegmentInfo of the segment this reader is reading.
    */
   SegmentInfo getSegmentInfo() {
     return si;
   }
 
   void setSegmentInfo(SegmentInfo info) {
     si = info;
   }
 
   void startCommit() {
     rollbackHasChanges = hasChanges;
     rollbackDeletedDocsDirty = deletedDocsDirty;
     rollbackNormsDirty = normsDirty;
     rollbackPendingDeleteCount = pendingDeleteCount;
     for (Norm norm : norms.values()) {
       norm.rollbackDirty = norm.dirty;
     }
   }
 
   void rollbackCommit() {
     hasChanges = rollbackHasChanges;
     deletedDocsDirty = rollbackDeletedDocsDirty;
     normsDirty = rollbackNormsDirty;
     pendingDeleteCount = rollbackPendingDeleteCount;
     for (Norm norm : norms.values()) {
       norm.dirty = norm.rollbackDirty;
     }
   }
 
   /** Returns the directory this index resides in. */
   @Override
   public Directory directory() {
     // Don't ensureOpen here -- in certain cases, when a
     // cloned/reopened reader needs to commit, it may call
     // this method on the closed original reader
     return core.dir;
   }
 
   // This is necessary so that cloned SegmentReaders (which
   // share the underlying postings data) will map to the
   // same entry in the FieldCache.  See LUCENE-1579.
   // nocommit - what to return here?
   @Override
   public final Object getFieldCacheKey() {
     return core;
   }
   
   // nocommit: missing?
   //@Override
   //public long getUniqueTermCount() {
   //  return core.getTermsReader().size();
   //}
 
 
   /**
    * Lotsa tests did hacks like:<br/>
    * SegmentReader reader = (SegmentReader) IndexReader.open(dir);<br/>
    * They broke. This method serves as a hack to keep hacks working
    * We do it with R/W access for the tests (BW compatibility)
    * @deprecated Remove this when tests are fixed!
    */
   static SegmentReader getOnlySegmentReader(Directory dir) throws IOException {
     return getOnlySegmentReader(IndexReader.open(dir, false));
   }
 
   static SegmentReader getOnlySegmentReader(IndexReader reader) {
     if (reader instanceof SegmentReader)
       return (SegmentReader) reader;
 
     if (reader instanceof DirectoryReader) {
       IndexReader[] subReaders = reader.getSequentialSubReaders();
       if (subReaders.length != 1)
         throw new IllegalArgumentException(reader + " has " + subReaders.length + " segments instead of exactly one");
 
       return (SegmentReader) subReaders[0];
     }
 
     throw new IllegalArgumentException(reader + " is not a SegmentReader or a single-segment DirectoryReader");
   }
 
   public int getTermInfosIndexDivisor() {
     return core.termsIndexDivisor;
   }
   
   // Back compat: legacy TermEnum API over flex API
   final private class LegacyTermEnum extends TermEnum {
     FieldsEnum fields;
     TermsEnum terms;
     boolean done;
     String currentField;
     TermRef currentTerm;
 
     public LegacyTermEnum(Term t) throws IOException {
      //System.out.println("sr.lte.init: term=" + t);
       fields = core.fields.iterator();
       currentField = fields.next();
       if (currentField == null) {
         done = true;
       } else if (t != null) {
        // Pre-seek
 
         // nocommit -- inefficient; do we need
         // FieldsEnum.seek? (but this is slow only for
         // legacy API, and, when field count is high)
         while(currentField.compareTo(t.field) < 0) {
           currentField = fields.next();
           if (currentField == null) {
             // Didn't find the field
             done = true;
             break;
           }
         }
 
         if (!done) {
          if (currentField == t.field) {
            // Field matches -- get terms
             terms = fields.terms();
             String text = t.text();
             TermRef tr;
             // this is a hack only for backwards compatibility.
             // previously you could supply a term ending with a lead surrogate,
             // and it would return the next Term.
             // if someone does this, tack on the lowest possible trail surrogate.
             // this emulates the old behavior, and forms "valid UTF-8" unicode.
             if (text.length() > 0 
                && Character.isHighSurrogate(text.charAt(text.length() - 1)))
               tr = new TermRef(t.text() + "\uDC00");
            else
               tr = new TermRef(t.text());
             TermsEnum.SeekStatus status = terms.seek(tr);
             if (status == TermsEnum.SeekStatus.END) {
              // leave currentTerm null
             } else if (status == TermsEnum.SeekStatus.FOUND) {
               currentTerm = tr;
             } else {
               currentTerm = terms.term();
             }
           }
         }
       } else {
         terms = fields.terms();
       }
     }
 
     @Override
     public boolean next() throws IOException {
 
       if (Codec.DEBUG) {
         System.out.println("tdte.next done=" + done + " seg=" + core.segment);
       }
 
       if (done) {
         return false;
       }
 
       while(true) {
         if (terms == null) {
           // Advance to the next field
           currentField = fields.next();
           if (currentField == null) {
             if (Codec.DEBUG)
               System.out.println("  fields.next returned false");
             done = true;
             return false;
           }
           terms = fields.terms();
         }
         currentTerm = terms.next();
         if (currentTerm != null) {
           // This field still has terms
           return true;
         } else {
          // Done producing terms from this field
           terms = null;
         }
       }
     }
 
     @Override
     public Term term() {
      if (terms != null && !done) {
        if (currentTerm != null) {
           return new Term(currentField, currentTerm.toString());
         }
      }
       return null;
     }
 
     @Override
     public int docFreq() {
       return terms == null ? 0 : terms.docFreq();
     }
 
     @Override
     public void close() {}
   }
 
   // Back compat: emulates legacy TermDocs API on top of
   // flex API
   private class LegacyTermDocs implements TermDocs {
 
     String currentField;
     final Fields fields;
     TermsEnum terms;
     DocsEnum docs;
     int doc;
 
     LegacyTermDocs() throws IOException {
       fields = core.fields;
     }
 
     public void close() {}
 
     public void seek(TermEnum termEnum) throws IOException {
       // nocommit -- optimize for the special cases here
       seek(termEnum.term());
     }
 
     public boolean skipTo(int target) throws IOException {
       if (docs == null) return false;
       doc = docs.advance(target);
       return doc != docs.NO_MORE_DOCS;
     }
 
     public int read(int[] docs, int[] freqs) throws IOException {
       if (this.docs == null) {
         return 0;
       }
       return this.docs.read(docs, freqs);
     }
 
     public void seek(Term term) throws IOException {
 
       if (Codec.DEBUG) {
         System.out.println("\nwrapper termdocs.seek term=" + term);
       }
 
       docs = null;
 
       if (terms != null && !term.field.equals(currentField)) {
         if (Codec.DEBUG) {
           System.out.println("  switch field");
         }
         if (terms != null) {
           terms = null;
         }
       }
 
       if (terms == null) {
         currentField = term.field;
         Terms terms1 = fields.terms(term.field);
         if (terms1 == null) {
           // no such field
           return;
         } else {
           terms = terms1.iterator();
         }
       }
 
       if (terms.seek(new TermRef(term.text)) == TermsEnum.SeekStatus.FOUND) {
         // Term exists
         docs = terms.docs(deletedDocs);
         if (Codec.DEBUG) {
           System.out.println("  init docs enum");
         }
       } else {
         docs = null;
         if (Codec.DEBUG) {
           System.out.println("  clear docs enum");
         }
       }
     }
 
     public int doc() {
       if (docs == null) return 0;
       else return doc;
     }
 
     public int freq() {
       if (docs == null) return 0;
       return docs.freq();
     }
 
     public boolean next() throws IOException {
       if (docs == null) return false;
       doc = docs.nextDoc();
       return doc != DocsEnum.NO_MORE_DOCS;
     }
   }
 
   // Back compat: implements legacy TermPositions API on top
   // of flex API
   final private class LegacyTermPositions extends LegacyTermDocs implements TermPositions {
 
     PositionsEnum positions;
 
     LegacyTermPositions() throws IOException {
       super();
     }
 
     @Override
     public void seek(TermEnum termEnum) throws IOException {
       super.seek(termEnum);
       if (docs != null)
         positions = docs.positions();
     }
 
     @Override
     public boolean skipTo(int target) throws IOException {
       boolean result = super.skipTo(target);
       positions = null;
       return result;
     }
 
     @Override
     public int read(int[] docs, int[] freqs) throws IOException {
       throw new UnsupportedOperationException("TermPositions does not support processing multiple documents in one call. Use TermDocs instead.");
     }
 
     @Override
     public void seek(Term term) throws IOException {
       super.seek(term);
       positions = null;
     }
 
     @Override
     public boolean next() throws IOException {
       boolean result = super.next();
       positions = null;
       return result;
     }
 
     public int nextPosition() throws IOException {     
       if (positions == null) {
         positions = docs.positions();
       }
       return positions.next();
     }
 
     public int getPayloadLength() {
       return positions.getPayloadLength();
     }
 
     public byte[] getPayload(byte[] data, int offset) throws IOException {
       return positions.getPayload(data, offset);
     }
 
     public boolean isPayloadAvailable() {
       return positions.hasPayload();
     }
   }
 
 
 }