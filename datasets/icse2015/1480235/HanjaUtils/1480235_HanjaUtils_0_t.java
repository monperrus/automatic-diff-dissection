 package org.apache.lucene.analysis.kr.utils;
 
 /*
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
 import java.util.HashMap;
 import java.util.List;
 import java.util.Map;
 
 import org.apache.lucene.analysis.kr.morph.MorphException;
 
 public class HanjaUtils {
 
   private static Map<String, char[]> mapHanja;
   
   public synchronized static void loadDictionary() throws MorphException {
     try {
       List<String> strList = FileUtil.readLines("org/apache/lucene/analysis/kr/dic/mapHanja.dic","UTF-8");
       mapHanja = new HashMap();    
     
       for(int i=0;i<strList.size();i++) {
         
         if(strList.get(i).length()<1||
             strList.get(i).indexOf(",")==-1) continue;
 
         String[] hanInfos = strList.get(i).split("[,]+");
 
         if(hanInfos.length!=2) continue;
         
         String hanja = StringEscapeUtil.unescapeJava(hanInfos[0]);
 
         mapHanja.put(hanja, hanInfos[1].toCharArray());
       }      
     } catch (IOException e) {
       throw new MorphException(e);
     }
   }
   
   /**
    * íìì ëìíë íê¸ì ì°¾ìì ë°ííë¤.
    * íëì íìë ì¬ë¬ ìì¼ë¡ ì½ì¼ ì ìì¼ë¯ë¡ ê°ë¥í ëª¨ë  ìì íê¸ë¡ ë°ííë¤.
    */
   public static char[] convertToHangul(char hanja) throws MorphException {
  
     if(mapHanja==null)  loadDictionary();
 
 //    if(hanja>0x9FFF||hanja<0x3400) return new char[]{hanja};
     
     char[] result = mapHanja.get(new String(new char[]{hanja}));
     if(result==null) return new char[]{hanja};
     
     return result;
   }
 }