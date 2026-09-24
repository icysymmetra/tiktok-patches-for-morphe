package app.morphe.extension.tiktok.diagnostics;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Experimental recorder. No Android dependencies; harness exercises this exact implementation. */
public final class FeedCapture {
    public static final int MAX_SNAPSHOT = 256 * 1024;
    private static final int MAX_FIELDS = 1024, MAX_NODES = 512, MAX_ELEMENTS = 128;
    private static final long WORK_NS = 12_000_000L;
    private final byte[] salt = new byte[32];
    private final int snapshotBudget;
    private final long workBudget;
    private final LinkedHashMap<Class<?>,Field[]> schemas=new LinkedHashMap<>(32,0.75f,true);
    private final LinkedHashMap<Class<?>,Field[]> objectSchemas=new LinkedHashMap<>(32,0.75f,true);
    public FeedCapture() { this(MAX_SNAPSHOT,WORK_NS); }
    public FeedCapture(int bytes,long nanos) {
        if(bytes<1024 || bytes>MAX_SNAPSHOT || nanos<=0)throw new IllegalArgumentException("capture budget");
        snapshotBudget=bytes;workBudget=nanos;new SecureRandom().nextBytes(salt);
    }
    private Field[] fields(Class<?> type) {
        Field[] result=schemas.get(type);
        if(result!=null)return result;
        result=type.getDeclaredFields();
        Arrays.sort(result,Comparator.comparing(Field::getName));
        if(result.length<=MAX_FIELDS){
            if(schemas.size()>=128)schemas.remove(schemas.keySet().iterator().next());
            schemas.put(type,result);
        }
        return result;
    }

    private Field[] objectFields(Class<?> type) {
        Field[] result=objectSchemas.get(type);
        if(result!=null)return result;
        ArrayList<Field> collected=new ArrayList<>();
        for(Class<?> current=type;current!=null && current!=Object.class;current=current.getSuperclass()) {
            for(Field field:fields(current)) {
                if(!Modifier.isStatic(field.getModifiers()))collected.add(field);
                if(collected.size()>MAX_FIELDS)break;
            }
            if(collected.size()>MAX_FIELDS)break;
        }
        collected.sort(Comparator.comparingInt(FeedCapture::fieldRank)
            .thenComparing(f->f.getDeclaringClass().getName()).thenComparing(Field::getName));
        result=collected.toArray(new Field[0]);
        if(objectSchemas.size()>=128)objectSchemas.remove(objectSchemas.keySet().iterator().next());
        objectSchemas.put(type,result);
        return result;
    }

    private static int fieldRank(Field field) {
        String owner=field.getDeclaringClass().getSimpleName();
        String name=field.getName();
        if(owner.equals("Aweme")) {
            if(name.equals("aid") || name.equals("groupId"))return 0;
            if(name.equals("awemeType") || name.equals("marketSubType") || name.equals("distributeType"))return 1;
            if(name.equals("recReasonsStruct") || name.equals("recReasonTag")
                    || name.equals("mNewUserRecommendedReason"))return 2;
            if(name.equals("mItemDistributeSource") || name.equals("mItemSourceCategory"))return 3;
            if(name.equals("region") || name.equals("descLanguage") || name.equals("textStickerMajorityLang"))return 3;
            if(name.equals("mRoomFeedCellStruct"))return 2;
        }
        if(isReasonClass(owner) || owner.equals("WTVValidationResponse")
                || owner.equals("WTVValidationRequest"))return 0;
        return 10;
    }

    private static boolean isReasonClass(String simpleName) {
        return simpleName.equals("RecReasonsStruct") || simpleName.equals("RecReasonEntry")
            || simpleName.equals("NewUserRecommendedReason")
            || simpleName.equals("NewUserRecommendReasonEntry");
    }

    private static boolean isAwemeType(Class<?> type) {
        for(Class<?> current=type;current!=null && current!=Object.class;current=current.getSuperclass())
            if(current.getSimpleName().equals("Aweme"))return true;
        return false;
    }

    private static boolean isReasonReference(Field field) {
        String owner=field.getDeclaringClass().getSimpleName();
        String name=field.getName();
        return isReasonClass(owner) || owner.equals("WTVValidationResponse")
            || owner.equals("WTVValidationRequest")
            || name.equals("recReasonsStruct") || name.equals("recReasonTag")
            || name.equals("mNewUserRecommendedReason") || name.equals("recReason")
            || name.equals("reasons") || name.equals("newUserRecommendedReasonEntryList");
    }

    private static boolean isPriorityReference(Field field) {
        String name=field.getName();
        return isReasonReference(field) || name.equals("mRoomFeedCellStruct")
            || name.equals("roomStruct") || name.equals("cellRoom") || name.equals("room");
    }

    private static boolean readableReasonText(Field field) {
        String owner=field.getDeclaringClass().getSimpleName();
        String name=field.getName();
        if(owner.equals("RecReasonsStruct"))return name.equals("title") || name.equals("body")
            || name.equals("subBody") || name.equals("urlText") || name.equals("buttonText")
            || name.equals("bizType");
        if(owner.equals("RecReasonEntry"))return name.equals("desc");
        if(owner.equals("NewUserRecommendReasonEntry"))return name.equals("recommendReason")
            || name.equals("detail") || name.equals("text");
        return false;
    }

    private static boolean readableMetadata(Field field,String value) {
        String owner=field.getDeclaringClass().getName(),name=field.getName();
        if(!owner.equals("com.ss.android.ugc.aweme.feed.model.Aweme"))return false;
        if(name.equals("mItemDistributeSource"))return value.matches("[A-Za-z][A-Za-z0-9_:-]{0,63}");
        if(name.equals("region"))return value.matches("[A-Za-z]{2}");
        if(name.equals("descLanguage") || name.equals("textStickerMajorityLang")
                || name.equals("photoTitleLanguageCode"))return value.matches("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8}){0,2}");
        return false;
    }

    public static final class Identities {
        private final ArrayDeque<java.lang.ref.WeakReference<Object>> refs=new ArrayDeque<>();
        private final ArrayDeque<Long> ids=new ArrayDeque<>();
        private long next=1;
        public synchronized long id(Object value){
            if(value==null)return 0;
            Iterator<java.lang.ref.WeakReference<Object>> r=refs.iterator();Iterator<Long> n=ids.iterator();
            while(r.hasNext()){Object found=r.next().get();long id=n.next();
                if(found==value)return id;
                if(found==null){r.remove();n.remove();}}
            if(refs.size()>=256){refs.removeFirst();ids.removeFirst();}
            long id=next++;refs.addLast(new java.lang.ref.WeakReference<>(value));ids.addLast(id);return id;
        }
    }

    public byte[] snapshot(Object owner, Object items, int depth) throws Exception {
        Graph g = new Graph(depth);
        try {
        String entries=g.reference(items,0);
        g.line("ROOT owner=" + g.reference(owner, 0) + " entries=" + entries
            +" uncompletedReferences=NOT_CAPTURED missingFields=NOT_CAPTURED");
        while (!g.pending.isEmpty()) {
            g.check();
            Node node = g.pending.remove();
            g.current=node;
            g.line("OBJECT @" + node.id + " class=C" + g.classKey(node.value.getClass()));
            if (node.depth > g.depth+(node.priority?2:0)) { g.line("BOUNDARY DEPTH_LIMIT"); continue; }
            Object value = node.value;
            Class<?> type = value.getClass();
            if (type.isArray()) {
                int length = Array.getLength(value);
                g.line("ARRAY length=" + length + " component=" + type.getComponentType().getName());
                if (type.getComponentType().isPrimitive()) { g.line("BOUNDARY PRIMITIVE_ARRAY_CONTENT"); continue; }
                for (int i=0; i<Math.min(length, MAX_ELEMENTS); i++) {
                    g.check(); g.line("[" + i + "]=" + g.reference(Array.get(value,i), node.depth+1,node.priority));
                }
                if (length>MAX_ELEMENTS) g.line("BOUNDARY ELEMENT_LIMIT omitted=" + (length-MAX_ELEMENTS));
            } else if (value instanceof List && safeList(type)) {
                List<?> list=(List<?>)value;
                int count=list.size();
                g.line("LIST size=" + count);
                for (int i=0;i<Math.min(count, MAX_ELEMENTS);i++) {
                    g.check(); g.line("[" + i + "]=" + g.reference(list.get(i), node.depth+1,node.priority));
                }
                if(count>MAX_ELEMENTS) g.line("BOUNDARY ELEMENT_LIMIT omitted="+(count-MAX_ELEMENTS));
                int after=list.size();
                g.line("END_LIST size=" + after + " consistency="+(after==count?"BEST_EFFORT":"CHANGE_DETECTED"));
            } else if (value instanceof Map && (type==HashMap.class || type==LinkedHashMap.class
                    || type==java.util.concurrent.ConcurrentHashMap.class)) {
                Map<?,?> map=(Map<?,?>)value;
                int before=map.size();g.line("MAP size="+before);int index=0;
                for(Map.Entry<?,?> entry:map.entrySet()) {
                    if(index>=MAX_ELEMENTS){g.line("BOUNDARY ELEMENT_LIMIT");break;}
                    g.check();g.line("ENTRY["+index+"] key="+g.reference(entry.getKey(),node.depth+1,node.priority)
                        +" value="+g.reference(entry.getValue(),node.depth+1,node.priority));index++;
                }
                int after=map.size();g.line("END_MAP size="+after+" capturedEntries="+index
                    +" consistency="+(before==after?"BEST_EFFORT":"CHANGE_DETECTED"));
            } else if (type.getName().startsWith("java.") || type.getName().startsWith("android.")
                    || type.getName().startsWith("androidx.")) {
                g.line("BOUNDARY FRAMEWORK_OBJECT");
            } else {
                if(node.fields==null){
                    node.fields=Arrays.asList(objectFields(type));
                    while(node.priorityFieldCount<node.fields.size()
                            && fieldRank(node.fields.get(node.priorityFieldCount))<10)node.priorityFieldCount++;
                    if(node.priorityFieldCount==0)node.priorityPassDone=true;
                }
                int until=node.aweme && !node.priorityPassDone
                    ? Math.min(node.priorityFieldCount,MAX_FIELDS)
                    : Math.min(Math.min(node.fields.size(),MAX_FIELDS),node.cursor+8);
                while(node.cursor<until) {
                        Field field=node.fields.get(node.cursor);
                        g.check();
                        String key=g.fieldKey(field.getDeclaringClass(),field);
                        if(field.isSynthetic()) { g.line(key+"=BOUNDARY_SYNTHETIC");node.cursor++;continue; }
                        try {
                            field.setAccessible(true);
                            Object v=field.get(value);
                            // Preserve unknown small discriminators; redact long/identifier-like numbers.
                            String name=field.getName().toLowerCase(Locale.ROOT);
                            boolean id=name.endsWith("id") || name.endsWith("ids") || field.getType()==long.class;
                            String captured;
                            if(v instanceof String && readableReasonText(field))captured=g.reasonText((String)v);
                            else if(v instanceof String && readableMetadata(field,(String)v))
                                captured="METADATA value=\""+v+"\" token="+token((String)v);
                            else if(id && v instanceof Number && ((Number)v).longValue()!=0)captured="NUMBER_TOKEN:"+token(v.toString());
                            else captured=g.reference(v,node.depth+1,node.priority || isPriorityReference(field));
                            g.line(key+"="+captured);
                        } catch(IllegalAccessException | SecurityException e) {
                            g.line(key+"=INACCESSIBLE:"+e.getClass().getSimpleName());
                        } catch(RuntimeException e) {
                            g.line(key+"=READ_ERROR:"+e.getClass().getSimpleName());
                        }
                        node.cursor++;
                }
                if(node.aweme && !node.priorityPassDone && node.cursor>=node.priorityFieldCount)
                    node.priorityPassDone=true;
                node.turns++;
                if(node.cursor<Math.min(node.fields.size(),MAX_FIELDS))g.pending.add(node);
                else g.line("END_OBJECT @"+node.id+" capturedFields="+node.cursor+" status="
                    +(node.fields.size()>MAX_FIELDS?"FIELD_LIMIT":"COMPLETE"));
            }
            g.current=null;
        }
        } catch(IOException e) {
            g.partial=e.getMessage();
        } catch(RuntimeException e) {
            g.partial="READ_ERROR:"+e.getClass().getSimpleName();
        }
        // Keep completed evidence even when a cooperative limit interrupts traversal.
        // Definitions are embedded so retention can never orphan a schema dependency.
        g.finish();
        return g.bytes.toByteArray();
    }

    private static boolean safeList(Class<?> c) {
        String n=c.getName();
        return n.equals("java.util.ArrayList") || n.equals("java.util.Arrays$ArrayList")
            || n.equals("java.util.Collections$SingletonList") || n.equals("java.util.Collections$EmptyList");
    }

    private String token(String value) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        d.update(salt);
        byte[] hash=d.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder b=new StringBuilder();
        final char[] hex="0123456789abcdef".toCharArray();
        for(int i=0;i<12;i++) { b.append(hex[(hash[i]>>>4)&15]);b.append(hex[hash[i]&15]); }
        return b.toString();
    }

    private final class Graph {
        final int depth;
        final long start=System.nanoTime();
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream(8192);
        final IdentityHashMap<Object,Node> seen=new IdentityHashMap<>();
        final PriorityQueue<Node> pending=new PriorityQueue<>(Comparator.comparingInt(Node::lane)
            .thenComparingInt(n->n.turns).thenComparingInt(n->n.depth).thenComparingInt(n->n.id));
        final LinkedHashMap<String,Integer> fieldIds=new LinkedHashMap<>();
        final LinkedHashMap<Class<?>,Integer> classIds=new LinkedHashMap<>();
        String partial;
        Node current;
        Graph(int depth) { this.depth=depth; }
        String fieldKey(Class<?> owner,Field field)throws IOException {
            int cid=classKey(owner);
            String key=owner.getName()+"."+field.getName();
            Integer fid=fieldIds.get(key);
            if(fid==null){fid=fieldIds.size()+1;line("FIELD F"+fid+" owner=C"+cid+" name="+field.getName()
                +" type="+field.getType().getName()+" modifiers="+field.getModifiers());fieldIds.put(key,fid);}
            return "F"+fid;
        }
        int classKey(Class<?> owner)throws IOException {
            Integer cid=classIds.get(owner);
            if(cid==null){cid=classIds.size()+1;line("CLASS C"+cid+"="+owner.getName());classIds.put(owner,cid);}
            return cid;
        }
        void finish()throws IOException {
            String ending="COVERAGE status="+(partial==null?"COMPLETE_WITH_BOUNDARIES":"PARTIAL")
                +" reason="+(partial==null?"NONE":partial)+" pendingObjects="+pending.size()
                +" nodes="+seen.size()+" interruptedObject="+(current==null?"NONE":"@"+current.id)
                +" nextField="+(current==null?-1:current.cursor)+" consistency=BEST_EFFORT\nEND_SNAPSHOT\n";
            bytes.write(ending.getBytes(StandardCharsets.UTF_8));
        }
        void check() throws IOException {
            if(System.nanoTime()-start>workBudget) throw new IOException("WORK_BUDGET");
        }
        void line(String text) throws IOException {
            byte[] data=(text+"\n").getBytes(StandardCharsets.UTF_8);
            if(bytes.size()+data.length>snapshotBudget-512) throw new IOException("BYTE_BUDGET");
            bytes.write(data);
        }
        String reasonText(String value) {
            if(value==null)return "NULL";
            StringBuilder safe=new StringBuilder(Math.min(value.length(),512));
            int limit=Math.min(value.length(),512);
            for(int i=0;i<limit;i++){
                char c=value.charAt(i);
                if(c=='\\' || c=='\"')safe.append('\\').append(c);
                else if(c=='\n')safe.append("\\n");
                else if(c=='\r')safe.append("\\r");
                else if(c=='\t')safe.append("\\t");
                else if(Character.isISOControl(c))safe.append('?');
                else safe.append(c);
            }
            return "WHY_TEXT length="+value.length()+" truncated="+(value.length()>limit)+" value=\""+safe+"\"";
        }
        String reference(Object v,int level) throws Exception {
            return reference(v,level,false);
        }
        String reference(Object v,int level,boolean priority) throws Exception {
            check();
            if(v==null) return "NULL";
            if(v instanceof Boolean || v instanceof Byte || v instanceof Short || v instanceof Integer
                || v instanceof Float || v instanceof Double) return v.getClass().getSimpleName()+":"+v;
            if(v instanceof Long) return ((Long)v)==0 ? "Long:0" : "LONG_TOKEN:"+token(v.toString());
            if(v instanceof Character) return "CHAR_REDACTED";
            if(v instanceof String) {
                String s=(String)v;
                if(s.isEmpty())return "EMPTY_STRING";
                return s.length()>2048 ? "STRING_REDACTED length="+s.length()+" equality=UNKNOWN"
                    : "STRING length="+s.length()+" token="+token(s);
            }
            if(v instanceof Enum) return "ENUM:"+v.getClass().getName()+":"+((Enum<?>)v).name();
            Node existing=seen.get(v);
            if(existing!=null) {
                if(priority && !existing.priority){
                    boolean queued=pending.remove(existing);existing.priority=true;if(queued)pending.add(existing);
                }
                return "@"+existing.id+"/C"+classKey(v.getClass());
            }
            // Verified reason chains need two extra hops for reasons -> list -> entry.
            // They remain bounded by the same node, byte, and work ceilings.
            if(level>depth+(priority?2:0))return "BOUNDARY_DEPTH_LIMIT class="+v.getClass().getName();
            if(seen.size()>=MAX_NODES) return "BOUNDARY_OBJECT_LIMIT class="+v.getClass().getName();
            int cid=classKey(v.getClass());
            int id=seen.size()+1;Node node=new Node(v,id,level,priority);
            seen.put(v,node);pending.add(node);return "@"+id+"/C"+cid;
        }
    }
    private static final class Node {
        final Object value; final int id,depth;
        final boolean aweme;boolean priority,priorityPassDone;
        List<Field> fields;int cursor,priorityFieldCount,turns;
        Node(Object v,int i,int d,boolean p) { value=v;id=i;depth=d;priority=p;aweme=isAwemeType(v.getClass()); }
        int lane(){
            if(depth==0 && (value instanceof List || value instanceof Map || value.getClass().isArray()))return 0;
            if(aweme&&!priorityPassDone)return 1;
            if(priority)return 2;
            return 3;
        }
    }

    /** Separate immutable snapshot store with lossless dedup; metadata is never deduplicated. */
    public static final class Ring {
        private final int budget;
        private final LinkedHashMap<Long,Blob> blobs=new LinkedHashMap<>();
        private final ArrayDeque<Event> events=new ArrayDeque<>();
        private int used; private long nextBlob=1, nextEvent=1;
        public long evicted, deduplicated, coalesced;
        public Ring(int bytes) { budget=bytes; }
        public synchronized void add(String metadata, byte[] raw) throws IOException {
            byte[] packed=compress(raw);
            Blob blob=null;
            // Identical redaction placeholders do not establish content equality.
            boolean equalityKnown=!new String(raw,StandardCharsets.UTF_8).contains("equality=UNKNOWN");
            if(equalityKnown)for(Blob b:blobs.values()) if(Arrays.equals(b.data,packed)) { blob=b; deduplicated++; break; }
            Event last=events.peekLast();
            // Only consecutive getter observations with exactly equal captured evidence.
            // Identity/settings/stage must also match; no intervening event is hidden.
            if(blob!=null && last!=null && last.blob==blob && metadata.contains("_GETTER ")
                    && metadata.replaceAll("atNs=[0-9]+","atNs=*").equals(last.metadata.replaceAll("atNs=[0-9]+","atNs=*"))){
                int change=metadata.getBytes(StandardCharsets.UTF_8).length-last.lastMetadata.getBytes(StandardCharsets.UTF_8).length;
                last.lastMetadata=metadata;last.occurrences++;last.cost+=change;used+=change;coalesced++;
                trim();return;
            }
            int eventCost=metadata.getBytes(StandardCharsets.UTF_8).length*2+160;
            int cost=eventCost+(blob==null?packed.length+128:0);
            if(cost>budget) throw new IOException("GROUP_OVERSIZE");
            if(blob==null) { blob=new Blob(nextBlob++,packed);blobs.put(blob.id,blob);used+=packed.length+128; }
            blob.refs++;
            events.addLast(new Event(nextEvent++,metadata,blob,eventCost));used+=eventCost;
            trim();
        }
        private void trim(){
            while(used>budget || events.size()>2048) {
                Event e=events.removeFirst(); used-=e.cost;evicted++;
                if(--e.blob.refs==0) { blobs.remove(e.blob.id);used-=e.blob.data.length+128; }
            }
        }
        public synchronized int bytes() { return used; }
        public synchronized int count() { return events.size(); }
        public synchronized void export(OutputStream out) throws IOException {
            write(out,"RING encodedAccountingBytes="+used+" budget="+budget+" events="+events.size()
                +" evicted="+evicted+" deduplicated="+deduplicated+" coalesced="+coalesced+"\n");
            for(Event e:events) {
                write(out,"EVENT E"+e.id+" snapshot=B"+e.blob.id+" occurrences="+e.occurrences+" "+e.metadata+"\n");
                if(e.occurrences>1)write(out,"LAST_OBSERVATION E"+e.id+" "+e.lastMetadata+"\n");
            }
            for(Blob b:blobs.values()) {
                write(out,"SNAPSHOT B"+b.id+"\n");
                try(InputStream in=new InflaterInputStream(new ByteArrayInputStream(b.data))) {
                    byte[] buffer=new byte[8192];int n;
                    while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                }
                write(out,"END B"+b.id+"\n");
            }
        }
        private static byte[] compress(byte[] raw) throws IOException {
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            Deflater deflater=new Deflater(1);
            try(DeflaterOutputStream out=new DeflaterOutputStream(b,deflater)){out.write(raw);}
            finally {deflater.end();}
            return b.toByteArray();
        }
        private static void write(OutputStream out,String s)throws IOException{out.write(s.getBytes(StandardCharsets.UTF_8));}
        private static final class Blob { final long id;final byte[] data;int refs;Blob(long id,byte[] data){this.id=id;this.data=data;} }
        private static final class Event {final long id;final String metadata;final Blob blob;int cost;long occurrences=1;String lastMetadata;
            Event(long id,String m,Blob b,int c){this.id=id;metadata=m;lastMetadata=m;blob=b;cost=c;}}
    }
}
