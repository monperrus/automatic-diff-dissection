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
 
 package org.apache.solr.servlet.cache;
 
 import java.io.IOException;
 import java.util.Collections;
 import java.util.Map;
 import java.util.WeakHashMap;
 import java.util.List;
 
 import javax.servlet.http.HttpServletRequest;
 import javax.servlet.http.HttpServletResponse;
 
 import org.apache.lucene.index.IndexReader;
 
 import org.apache.solr.core.SolrCore;
 import org.apache.solr.core.SolrConfig;
 import org.apache.solr.core.SolrConfig.HttpCachingConfig.LastModFrom;
 import org.apache.solr.common.SolrException;
 import org.apache.solr.common.SolrException.ErrorCode;
 import org.apache.solr.search.SolrIndexSearcher;
 import org.apache.solr.request.SolrQueryRequest;
 import org.apache.solr.request.SolrRequestHandler;
 
 import org.apache.commons.codec.binary.Base64;
 
 public final class HttpCacheHeaderUtil {
   
   public static void sendNotModified(HttpServletResponse res)
     throws IOException {
     res.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
   }
 
   public static void sendPreconditionFailed(HttpServletResponse res)
     throws IOException {
     res.setStatus(HttpServletResponse.SC_PRECONDITION_FAILED);
   }
   
   /**
    * Weak Ref based cache for keeping track of core specific etagSeed
    * and the last computed etag.
    *
    * @see #calcEtag
    */
   private static Map<SolrCore, EtagCacheVal> etagCoreCache
     = new WeakHashMap<SolrCore, EtagCacheVal>();
 
   /** @see #etagCoreCache */
   private static class EtagCacheVal {
     private final String etagSeed;
     
     private String etagCache = null;
     private long indexVersionCache=-1;
     
     public EtagCacheVal(final String etagSeed) {
       this.etagSeed = etagSeed;
     }
 
     public String calcEtag(final long currentIndexVersion) {
       if (currentIndexVersion != indexVersionCache) {
         indexVersionCache=currentIndexVersion;
         
         etagCache = "\""
           + new String(Base64.encodeBase64((Long.toHexString
                                             (Long.reverse(indexVersionCache))
                                             + etagSeed).getBytes()))
           + "\"";
       }
       
       return etagCache;
     }
   }
   
   /**
    * Calculates a tag for the ETag header.
    *
    * @param solrReq
    * @return a tag
    */
   public static String calcEtag(final SolrQueryRequest solrReq) {
     final SolrCore core = solrReq.getCore();
     final long currentIndexVersion
       = solrReq.getSearcher().getReader().getVersion();
 
     EtagCacheVal etagCache = etagCoreCache.get(core);
     if (null == etagCache) {
       final String etagSeed
         = core.getSolrConfig().getHttpCachingConfig().getEtagSeed();
       etagCache = new EtagCacheVal(etagSeed);
       etagCoreCache.put(core, etagCache);
     }
     
     return etagCache.calcEtag(currentIndexVersion);
     
   }
 
   /**
    * Checks if one of the tags in the list equals the given etag.
    * 
    * @param headerList
    *            the ETag header related header elements
    * @param etag
    *            the ETag to compare with
    * @return true if the etag is found in one of the header elements - false
    *         otherwise
    */
   public static boolean isMatchingEtag(final List<String> headerList,
       final String etag) {
     for (String header : headerList) {
       final String[] headerEtags = header.split(",");
       for (String s : headerEtags) {
         s = s.trim();
         if (s.equals(etag) || "*".equals(s)) {
           return true;
         }
       }
     }
 
     return false;
   }
 
   /**
    * Calculate the appropriate last-modified time for Solr relative the current request.
    * 
    * @param solrReq
    * @return the timestamp to use as a last modified time.
    */
   public static long calcLastModified(final SolrQueryRequest solrReq) {
     final SolrCore core = solrReq.getCore();
     final SolrIndexSearcher searcher = solrReq.getSearcher();
     
     final LastModFrom lastModFrom
       = core.getSolrConfig().getHttpCachingConfig().getLastModFrom();
 
     long lastMod;
     try {
       // assume default, change if needed (getOpenTime() should be fast)
       lastMod =
         LastModFrom.DIRLASTMOD == lastModFrom
         ? IndexReader.lastModified(searcher.getReader().directory())
         : searcher.getOpenTime();
     } catch (IOException e) {
       // we're pretty freaking screwed if this happens
       throw new SolrException(ErrorCode.SERVER_ERROR, e);
     }
     // Get the time where the searcher has been opened
     // We get rid of the milliseconds because the HTTP header has only
     // second granularity
     return lastMod - (lastMod % 1000L);
   }
 
   /**
    * Set the Cache-Control HTTP header (and Expires if needed)
    * based on the SolrConfig.
    */
   public static void setCacheControlHeader(final SolrConfig conf,
                                           final HttpServletResponse resp) {

     final String cc = conf.getHttpCachingConfig().getCacheControlHeader();
     if (null != cc) {
       resp.setHeader("Cache-Control", cc);
     }
     Long maxAge = conf.getHttpCachingConfig().getMaxAge();
     if (null != maxAge) {
       resp.setDateHeader("Expires", System.currentTimeMillis()
                          + (maxAge * 1000L));
     }
 
     return;
   }
 
   /**
    * Sets HTTP Response cache validator headers appropriately and
    * validates the HTTP Request against these using any conditional
    * request headers.
    *
    * If the request contains conditional headers, and those headers
    * indicate a match with the current known state of the system, this
    * method will return "true" indicating that a 304 Status code can be
    * returned, and no further processing is needed.
    *
    * 
    * @return true if the request contains conditional headers, and those
    *         headers indicate a match with the current known state of the
    *         system -- indicating that a 304 Status code can be returned to
    *         the client, and no further request processing is needed.  
    */
   public static boolean doCacheHeaderValidation(final SolrQueryRequest solrReq,
                                                 final HttpServletRequest req,
                                                 final HttpServletResponse resp)
     throws IOException {
 
    final Method reqMethod=Method.getMethod(req.getMethod());
     
     final long lastMod = HttpCacheHeaderUtil.calcLastModified(solrReq);
     final String etag = HttpCacheHeaderUtil.calcEtag(solrReq);
     
     resp.setDateHeader("Last-Modified", lastMod);
     resp.setHeader("ETag", etag);
 
     if (checkETagValidators(req, resp, reqMethod, etag)) {
       return true;
     }
 
     if (checkLastModValidators(req, resp, lastMod)) {
       return true;
     }
 
     return false;
   }
   
 
   /**
    * Check for etag related conditional headers and set status 
    * 
    * @return true if no request processing is necessary and HTTP response status has been set, false otherwise.
    * @throws IOException
    */
   @SuppressWarnings("unchecked")
   public static boolean checkETagValidators(final HttpServletRequest req,
                                             final HttpServletResponse resp,
                                             final Method reqMethod,
                                             final String etag)
     throws IOException {
     
     // First check If-None-Match because this is the common used header
     // element by HTTP clients
     final List<String> ifNoneMatchList = Collections.list(req
         .getHeaders("If-None-Match"));
     if (ifNoneMatchList.size() > 0 && isMatchingEtag(ifNoneMatchList, etag)) {
       if (reqMethod == Method.GET || reqMethod == Method.HEAD) {
         sendNotModified(resp);
       } else {
         sendPreconditionFailed(resp);
       }
       return true;
     }
 
     // Check for If-Match headers
     final List<String> ifMatchList = Collections.list(req
         .getHeaders("If-Match"));
     if (ifMatchList.size() > 0 && !isMatchingEtag(ifMatchList, etag)) {
       sendPreconditionFailed(resp);
       return true;
     }
 
     return false;
   }
 
   /**
    * Check for modify time related conditional headers and set status 
    * 
    * @return true if no request processing is necessary and HTTP response status has been set, false otherwise.
    * @throws IOException
    */
   public static boolean checkLastModValidators(final HttpServletRequest req,
                                                final HttpServletResponse resp,
                                                final long lastMod)
     throws IOException {
 
     try {
       // First check for If-Modified-Since because this is the common
       // used header by HTTP clients
       final long modifiedSince = req.getDateHeader("If-Modified-Since");
       if (modifiedSince != -1L && lastMod <= modifiedSince) {
         // Send a "not-modified"
         sendNotModified(resp);
         return true;
       }
       
       final long unmodifiedSince = req.getDateHeader("If-Unmodified-Since");
       if (unmodifiedSince != -1L && lastMod > unmodifiedSince) {
         // Send a "precondition failed"
         sendPreconditionFailed(resp);
         return true;
       }
     } catch (IllegalArgumentException iae) {
       // one of our date headers was not formated properly, ignore it
       /* NOOP */
     }
     return false;
   }
 }