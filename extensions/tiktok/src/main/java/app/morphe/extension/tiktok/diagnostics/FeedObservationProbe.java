package app.morphe.extension.tiktok.diagnostics;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.preference.Preference;
import android.view.View;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

public final class FeedObservationProbe {
    public static volatile boolean installed;
    private static volatile Session session;
    private static final ThreadLocal<Token> current=new ThreadLocal<>();
    private static final AtomicLong calls=new AtomicLong();
    private FeedObservationProbe() {}
    public static void enable(){installed=true;}
    public static boolean active(){Session s=session;return installed && s!=null && s.active;}

    public static final class Token {
        final Session session;final String source;final long call;final Token parent;
        final StringBuilder decisions=new StringBuilder();
        byte[] before;
        boolean recording=true;
        long beforeNs,afterNs,inputIdentity,outputIdentity;
        Token(Session s,String source){session=s;this.source=source;call=calls.incrementAndGet();parent=current.get();}
    }
    public static Token begin(String source,Object owner,Object list) {
        if(!active())return null;
        Session s=session;
        if(s==null || !s.enter())return null;
        s.count("hookCalls."+source);
        if(!s.pairs.tryAcquire()){
            s.gap(source,"PAIR_LIMIT");s.leave();Token muted=new Token(s,source);muted.recording=false;current.set(muted);return muted;
        }
        if(!s.pending.tryAcquire()) {
            s.pairs.release();s.gap(source,"QUEUE_CAPACITY_BEFORE_CAPTURE");s.leave();
            Token muted=new Token(s,source);muted.recording=false;current.set(muted);return muted;
        }
        Token t=new Token(s,source);
        current.set(t);
        t.beforeNs=System.nanoTime()-s.start;t.inputIdentity=s.identities.id(list);
        t.before=s.read(source,owner,list);
        return t;
    }
    public static void end(Token t,Object owner,Object list) {
        if(t==null)return;
        if(!t.recording){if(t.parent==null)current.remove();else current.set(t.parent);return;}
        try {
            t.afterNs=System.nanoTime()-t.session.start;t.outputIdentity=t.session.identities.id(list);
            byte[] after=t.session.read(t.source,owner,list);
            ByteArrayOutputStream group=new ByteArrayOutputStream();
            group.write("BEFORE\n".getBytes(StandardCharsets.UTF_8));
            group.write(t.before==null?"GAP INPUT_NOT_CAPTURED\n".getBytes(StandardCharsets.UTF_8):t.before);
            group.write("AFTER\n".getBytes(StandardCharsets.UTF_8));
            group.write(after==null?"GAP OUTPUT_NOT_CAPTURED\n".getBytes(StandardCharsets.UTF_8):after);
            t.session.submit(t.source,"hook="+t.source+" call="+t.call+" atNs="+(System.nanoTime()-t.session.start)
                +" parentCall="+(t.parent==null?0:t.parent.call)
                +" beforeNs="+t.beforeNs+" afterNs="+t.afterNs+" inputIdentity="+t.inputIdentity+" outputIdentity="+t.outputIdentity
                +" thread="+Thread.currentThread().getId()+" "+settings()+" decisions="+t.decisions,group.toByteArray());
        } catch(Exception e){t.session.pending.release();t.session.gap(t.source,"FINISH_ERROR");}
        finally {t.before=null;t.session.pairs.release();t.session.leave();if(t.parent==null)current.remove();else current.set(t.parent);}
    }
    public static void note(String reason) {
        Token t=current.get();if(t==null||!t.recording)return;
        if(t.decisions.length()<8192)t.decisions.append('[').append(reason).append(']');
        else t.session.count("gap.DECISION_LIMIT");
    }
    public static void mainGetter(Object list){observe("MAIN_EFFECTIVE_GETTER",null,list);}
    public static void followGetter(Object list){observe("FOLLOW_EFFECTIVE_GETTER",null,list);}
    public static void profileNativeTransform(Object list){observe("PROFILE_NATIVE_TRANSFORM_INPUT",null,list);}
    public static void cacheChain(Object payload){observe("CACHE_CHAIN_PAYLOAD",payload,null);}
    public static void reachBottom(Object payload){observe("REACH_BOTTOM_PAYLOAD",payload,null);}
    public static void finalInsert(Object payload){observe("FINAL_INSERT_PAYLOAD",payload,null);}
    public static void followPost(Object payload){observe("FOLLOW_NATIVE_POST_INPUT",payload,null);}
    public static void profileDetail(Object payload){observe("PROFILE_DETAIL_EVENT",payload,null);}
    public static void whyThisPostRequest(Object payload){observe("WHY_THIS_POST_REQUEST",payload,null);}
    public static void whyThisPostResponse(Object payload){observe("WHY_THIS_POST_RESPONSE",payload,null);}
    public static void whyThisPostResponseConsumed(Object payload){observe("WHY_THIS_POST_RESPONSE_CONSUMED",payload,null);}
    public static void whyThisPostLayout(Object item){observe("WHY_THIS_POST_LAYOUT",null,item);}
    public static void whyThisPostPanel(Object item,Object unusedSharePackage,Object reasons){
        // The middle register is passed only to keep the target invoke-range contiguous.
        // No share-package fields are traversed.
        observe("WHY_THIS_POST_PANEL_INPUT",reasons,item);
    }
    private static void observe(String hook,Object owner,Object list) {
        Session s=session;
        if(!installed || s==null)return;
        s.record(hook,"RETURN",owner,list);
    }
    private static String settings() {
        return "installed.feedFilter="+SettingsStatus.feedFilterEnabled
            +" installed.hideAi="+SettingsStatus.hideAiContentEnabled
            +" installed.hideFypSlop="+SettingsStatus.hideFypSlopEnabled
            +" settings.hideAi="+Settings.HIDE_AI_CONTENT.get()
            +" settings.hideFypSlop="+Settings.HIDE_ALTERNATE_FOR_YOU_BATCHES.get()
            +" settings.removeAds="+Settings.REMOVE_ADS.get()+" settings.hideLive="+Settings.HIDE_LIVE.get()
            +" settings.hideShop="+Settings.HIDE_SHOP.get()+" settings.hideStories="+Settings.HIDE_STORY.get()
            +" settings.hideImages="+Settings.HIDE_IMAGE.get()+" settings.filterOffline="+Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.get();
    }
    private static final class Session {
        final String id=UUID.randomUUID().toString();
        final int depth;
        Session(int depth){this.depth=depth;}
        final long wall=System.currentTimeMillis(), start=System.nanoTime();
        final FeedCapture capture=new FeedCapture();
        final FeedCapture.Identities identities=new FeedCapture.Identities();
        final FeedCapture.Ring ring=new FeedCapture.Ring(4*1024*1024);
        final AtomicBoolean reading=new AtomicBoolean();
        final Semaphore pairs=new Semaphore(2);
        // Reserve a queue slot before inspecting live objects; never discard paid-for traversal.
        // At most four immutable groups plus the two active filter pairs, no caller blocking.
        final Semaphore pending=new Semaphore(4);
        final AtomicLong dropped=new AtomicLong(),observed=new AtomicLong(),nanos=new AtomicLong(),maxNs=new AtomicLong();
        final ConcurrentHashMap<String,AtomicLong> health=new ConcurrentHashMap<>();
        final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"MorpheFeedCapture");t.setDaemon(true);return t;});
        volatile boolean active=true;
        int operations;
        final ConcurrentHashMap<String,Long> getterChecked=new ConcurrentHashMap<>();
        synchronized boolean enter(){if(!active)return false;operations++;return true;}
        synchronized void leave(){if(--operations==0 && !active)worker.shutdown();}
        long stopped;
        void count(String key){health.computeIfAbsent(key,k->new AtomicLong()).incrementAndGet();}
        void record(String hook,String stage,Object owner,Object list) {
            if(!enter())return;
            try {
                count("hookCalls."+hook);
                if(hook.endsWith("_GETTER") || hook.equals("WHY_THIS_POST_LAYOUT")){
                    if(pairs.availablePermits()<2){count("skipped."+hook+".FILTER_CAPTURE_ACTIVE");return;}
                    long now=System.nanoTime();
                    synchronized(getterChecked){
                        Long last=getterChecked.get(hook);
                        if(last!=null && now-last<250_000_000L){
                            count("skipped."+hook+".NOT_INSPECTED_RATE_LIMIT");return;
                        }
                        getterChecked.put(hook,now);
                    }
                }
                if(!pending.tryAcquire()){gap(hook,"QUEUE_CAPACITY_BEFORE_CAPTURE");return;}
                byte[] raw=read(hook,owner,list);
                if(raw!=null)submit(hook,"hook="+hook+" "+stage+" atNs="+(System.nanoTime()-start)
                    +" ownerIdentity="+identities.id(owner)+" identity="+identities.id(list)+" "+settings(),raw);
                else pending.release();
            } finally {leave();}
        }
        byte[] read(String hook,Object owner,Object list) {
            count("reads."+hook);observed.incrementAndGet();
            if(!reading.compareAndSet(false,true)){gap(hook,"CONCURRENT_CAPTURE");return null;}
            long begin=System.nanoTime();
            try {
                byte[] raw=capture.snapshot(owner,list,depth);
                String tail=new String(raw,Math.max(0,raw.length-512),Math.min(raw.length,512),StandardCharsets.UTF_8);
                count((tail.contains("status=PARTIAL")?"partialCaptures.":"finishedCaptures.")+hook);
                return raw;
            } catch(Exception e) {gap(hook,e instanceof IOException ? e.getMessage():"CAPTURE_"+e.getClass().getSimpleName());}
            finally {
                long elapsed=System.nanoTime()-begin;nanos.addAndGet(elapsed);
                String bucket=elapsed<=1_000_000L?"LE_1MS":elapsed<=4_000_000L?"LE_4MS":elapsed<=8_000_000L?"LE_8MS":elapsed<=12_000_000L?"LE_12MS":"GT_12MS";
                count("inspectionTime."+hook+"."+(Looper.myLooper()==Looper.getMainLooper()?"MAIN.":"BACKGROUND.")+bucket);
                maxNs.accumulateAndGet(elapsed,Math::max);reading.set(false);
            }
            return null;
        }
        void event(String hook,String detail) {
            if(!pending.tryAcquire()){gap(hook,"QUEUE_CAPACITY_BEFORE_CAPTURE");return;}
            submit(hook,"hook="+hook+" "+detail+" atNs="+(System.nanoTime()-start),new byte[0]);
        }
        void submit(String hook,String metadata,byte[] raw) {
            try { worker.execute(()->{
                try {ring.add(metadata,raw);}
                catch(Exception e){gap(hook,"STORE_"+e.getClass().getSimpleName());}
                finally {pending.release();}
            }); } catch(RejectedExecutionException e){pending.release();gap(hook,"WORKER_REJECTED");}
        }
        void gap(String hook,String why){dropped.incrementAndGet();count("gap."+hook+"."+why);}
        synchronized void stop(){if(!active)return;active=false;stopped=System.currentTimeMillis();if(operations==0)worker.shutdown();}
        void export(OutputStream out)throws IOException {
            StringBuilder h=new StringBuilder("MORPHE FEED OBSERVATION PROBE schema=4\n");
            h.append("session=").append(id).append(" startedUtcMillis=").append(wall).append(" stoppedUtcMillis=").append(stopped)
                .append("\ninstalledHooks=EXTENSION_FILTER_BOUNDARIES,MAIN_EFFECTIVE_GETTER,FOLLOW_EFFECTIVE_GETTER,PROFILE_NATIVE_TRANSFORM_INPUT,CACHE_CHAIN_PAYLOAD,FINAL_INSERT_PAYLOAD,FOLLOW_NATIVE_POST_INPUT,PROFILE_DETAIL_EVENT,WHY_THIS_POST_REQUEST,WHY_THIS_POST_RESPONSE,WHY_THIS_POST_PANEL_INPUT,WHY_THIS_POST_RESPONSE_CONSUMED,WHY_THIS_POST_LAYOUT")
                .append("\ntiktok=").append(Utils.getAppVersionName()).append(" bundle=").append(Utils.getPatchesReleaseVersion())
                .append("\nadditionalHooks=WHY_THIS_POST_PANEL_INPUT,WHY_THIS_POST_RESPONSE_CONSUMED,WHY_THIS_POST_LAYOUT")
                .append("\nhookSemantics=CONSTRUCTORS_ARE_NOT_NETWORK_COMPLETION PANEL_INPUT_IS_NOT_PROOF_OF_DISPLAY")
                .append("\nretention=SIZE_ONLY payloadBudget=4194304 pendingMax=4x524400 admission=BEFORE_INSPECTION activePairsMax=2")
                .append("\ninspectionDepth=").append(depth).append(" nodesPerSnapshot=512 elementsPerContainer=128 workBudgetNs=12000000 getterMinIntervalNs=250000000")
                .append("\nencoding=SELF_CONTAINED_FIELD_SCHEMAS traversal=MEMBERSHIP_THEN_ITEM_PRIORITY_THEN_REASON_THEN_FAIR_8_FIELD_TURNS rateLimitedObservations=NOT_INSPECTED")
                .append("\nstaticFields=EXCLUDED syntheticFields=BOUNDARY unknownStrings=TOKENIZED longStringEquality=UNKNOWN zeroIdentifiers=PRESERVED")
                .append("\nidentity=SNAPSHOT_LOCAL contentEquality=SESSION_STRING_TOKENS physicalIdentity=WEAK_256_NO_TOKEN_REUSE")
                .append("\nprivacy=STRINGS_AND_LONGS_REDACTED exceptVerifiedWhyThisPostText=READABLE_AND_BOUNDED validatedAwemeLanguageRegionSource=READABLE_WITH_TOKEN unknownNumericIdentifiersMayNeedSchemaReview")
                .append("\nconsistency=BEST_EFFORT partialCapturesRetainCompletedFields=true gapsAreCountersNoItemDataCaptured")
                .append("\ncaptureAttempts=").append(observed).append(" gapCount=").append(dropped)
                .append(" captureTotalNs=").append(nanos).append(" captureMaxNs=").append(maxNs).append('\n');
            new TreeMap<>(health).forEach((k,v)->h.append(k).append('=').append(v).append('\n'));
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));ring.export(out);
        }
    }
    public static Preference controls(Context context) {
        Preference p=new Preference(context) {
            @Override protected void onBindView(View view) {
                super.onBindView(view);
                app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
            }
        };
        p.setTitle("Feed debugger");
        p.setSummary("Capture a rolling record of feed items, filtering, and recommendation reasons.");
        p.setOnPreferenceClickListener(x->{
            Session s=session;
            String info=s==null?"No capture yet.": "Active="+s.active+"; retained events="+s.ring.count()
                +"; encoded bytes="+s.ring.bytes()+"; dropped="+s.dropped;
            android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(context)
                .setTitle("Feed debugger").setMessage(info)
                .setPositiveButton("Start new",(d,w)->{
                    Session old=session;
                    if(saving.get()){Utils.showToastLong("Wait for export to finish.");return;}
                    if(old!=null){old.stop();if(!old.worker.isTerminated()){Utils.showToastLong("Previous capture stopping. Try Start new again.");return;}}
                    android.app.AlertDialog depthDialog=new android.app.AlertDialog.Builder(context).setTitle("Capture depth")
                        .setItems(new String[]{"Shallow: fields and object references", "Nested: inspect referenced objects"},(picker,choice)->{
                            Session previous=session;
                            if(saving.get() || (previous!=null && !previous.worker.isTerminated())){
                                Utils.showToastLong("Stop the previous capture before starting another.");return;
                            }
                            session=new Session(choice==0?1:3);
                            Utils.showToastLong("Feed capture started. Reproduce, then stop and save.");
                        }).create();
                    depthDialog.show();
                    SettingsUi.styleStandardAlertDialog(depthDialog);
                })
                .setNeutralButton("Stop and save",(d,w)->save(context.getApplicationContext()))
                .setNegativeButton("Close",null).create();
            dialog.show();
            SettingsUi.styleStandardAlertDialog(dialog);
            return true;
        });
        return p;
    }
    private static final AtomicBoolean saving=new AtomicBoolean();
    private static void save(Context context) {
        Session s=session;
        if(s==null){Utils.showToastLong("No feed capture to save.");return;}
        if(!saving.compareAndSet(false,true))return;
        s.stop();
        new Thread(()->{
            Uri pending=null;
            try {
                if(!s.worker.awaitTermination(15,TimeUnit.SECONDS))throw new IOException("Capture worker still finishing; retry save.");
                String name="morphe-feed-probe-"+s.id+".txt";
                if(Build.VERSION.SDK_INT>=29){
                    ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE,"text/plain");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/Morphe");
                    v.put(MediaStore.MediaColumns.IS_PENDING,1);
                    pending=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
                    if(pending==null)throw new IOException("Cannot create export");
                    try(OutputStream out=context.getContentResolver().openOutputStream(pending)){if(out==null)throw new IOException("Cannot open export");s.export(out);}
                    v.clear();v.put(MediaStore.MediaColumns.IS_PENDING,0);context.getContentResolver().update(pending,v,null,null);
                }else{
                    File directory=context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                    if(directory==null)throw new IOException("No export directory");
                    if(!directory.exists()&&!directory.mkdirs())throw new IOException("Cannot create export directory");
                    try(OutputStream out=new FileOutputStream(new File(directory,name))){s.export(out);}
                }
                Utils.showToastLong("Feed probe saved: "+name);
            }catch(Exception e){
                if(pending!=null)context.getContentResolver().delete(pending,null,null);
                Utils.showToastLong("Feed export failed; stopped capture retained for retry.");
            }finally{saving.set(false);}
        },"MorpheFeedExport").start();
    }
}
