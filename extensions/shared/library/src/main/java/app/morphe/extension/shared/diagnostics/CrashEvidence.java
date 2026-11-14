/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.shared.diagnostics;

import android.content.Context;
import android.os.SystemClock;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads existing error files only. No new handler, callback, file store or target hook. */
final class CrashEvidence {
    // Keep the whole existing 64,000-byte private record, outside the 256-KiB incident store.
    static final int MAX_BODY_BYTES=64_000, MAX_SECTION_BYTES=2*MAX_BODY_BYTES+4*1024;
    private static final long CLOCK_TOLERANCE=10_000;
    private CrashEvidence() {}

    static String capturePreference(Context context) {
        try { return context.getSharedPreferences("morphe_prefs",Context.MODE_PRIVATE)
                .getBoolean("morphe_capture_java_crashes",false)?"on":"off"; }
        catch(Exception unavailable){return "unknown";}
    }
    private static final class Intake {
        final LogBufferManager.CrashReportSnapshot files; final String preference;
        Intake(Context context){files=LogBufferManager.readCrashReportsForRecovery(context);preference=capturePreference(context);}
    }
    static String collect(Context context, JSONObject state) {
        try {
            Intake intake=RecoveryCapture.probe(()->new Intake(context),1000);
            return render(state,context.getPackageName(),intake.preference,intake.files,
                    System.currentTimeMillis(),SystemClock.elapsedRealtime());
        } catch(Exception unavailable) {
            String status=unavailable instanceof RecoveryCapture.ProbeBusy?"probe_busy":
                    unavailable instanceof java.util.concurrent.TimeoutException?"read_timeout":"read_error";
            return header(state,"unknown")+"channel=java read_status="+status+"\nchannel=npth read_status="+status+"\n";
        }
    }

    private static String header(JSONObject state,String preference) {
        String trial=state.optString("trial");if(trial.length()>128)trial=trial.substring(0,128);
        StringBuilder out=new StringBuilder("MORPHE DIAGNOSTIC REPORT\nschema: 1\nsource: persisted_crash_evidence\ntrial: "+trial
                +"\nrecords_scope: latest persisted file per channel, not exhaustive error history"
                +"\ncollector_capture_preference_at_read: "+preference
                +"\nRecord timestamps describe capture, not necessarily the initiating exception time."
                +"\nHandler installation and failed-process capture are unknown unless recorded."
                +"\nAbsence, unavailable capture, and a missing initiating error are not proof of error-free execution.\n");
        JSONArray sessions=state.optJSONArray("sessions");
        if(sessions!=null)for(int i=Math.max(0,sessions.length()-4);i<sessions.length();i++){
            JSONObject s=sessions.optJSONObject(i);if(s==null)continue;
            out.append("tracked_session pid=").append(s.optInt("pid")).append(" started_ms=").append(s.optLong("at"))
                    .append(" process_started_elapsed_ms=").append(s.optLong("process_started_elapsed_ms",-1))
                    .append(" capture_preference_at_start=").append(preference(s.optString("capture_preference_at_start")))
                    .append(" handler_installation=not_observed\n");
        }
        return out.toString();
    }
    static String render(JSONObject state,String packageName,String preference,
            LogBufferManager.CrashReportSnapshot files,long now,long elapsed) {
        String result=header(state,preference)+record("java",files.java,state,packageName,now,elapsed)
                +record("npth",files.npth,state,packageName,now,elapsed);
        return clip(result,MAX_SECTION_BYTES,"\n[supplement truncated]\n");
    }
    private static final class Association {
        final String status; final JSONObject session; final boolean include;
        Association(String status,JSONObject session,boolean include){this.status=status;this.session=session;this.include=include;}
    }
    private static Association excluded(String status){return new Association(status,null,false);}

    private static String record(String channel,LogBufferManager.PersistedCrashReport record,
            JSONObject state,String packageName,long now,long elapsed) {
        String prefix="\n[PERSISTED "+channel.toUpperCase(Locale.ROOT)+" RECORD]\nchannel="+channel+" read_status="+record.status;
        String storage=" storage_state="+record.storage;
        if(!record.status.equals("present"))return prefix+storage+"\n";
        Map<String,String> h=fields(record.text);
        if(h==null || !"1".equals(h.get("schema")) ||
                !(channel.equals("java")?record.text.contains("\n[STACK TRACE]\n"):
                    "tiktok_npth_callback".equals(h.get("source")) && record.text.contains("\n[NPTH SUMMARY]\n")))
            return prefix+" association=malformed_record"+storage+"\n";
        Association a=associate(h,state,packageName,now,elapsed);
        StringBuilder out=new StringBuilder(prefix).append(" association=").append(a.status).append(storage).append('\n');
        if(!a.include)return out.toString();
        out.append("record_pid=").append(number(h,"pid")).append(" record_timestamp_ms=").append(timestamp(h.get("timestamp_utc")))
                .append(" source_record_complete=").append("true".equals(h.get("complete"))?"true":"false".equals(h.get("complete"))?"false":"unknown").append('\n');
        out.append("source_truncation_marker_seen=").append(record.text.contains("[throwable trace truncated]")
                ||record.text.contains("[report truncated]")||record.text.contains("[summary truncated]")
                ||record.text.contains("[diagnostic text truncated]")).append('\n');
        if(a.session!=null)out.append("matched_session_started_ms=").append(a.session.optLong("at"))
                .append(" capture_preference_at_start=").append(preference(a.session.optString("capture_preference_at_start"))).append('\n');
        else out.append("No tracked process-session association; do not attribute this record to a recovered ANR.\n");
        String safe=DiagnosticRedactor.redact(record.text);
        boolean cut=safe.getBytes(StandardCharsets.UTF_8).length>MAX_BODY_BYTES;
        if(cut)safe=safe.replaceFirst("(?m)^complete: true$","complete: false");
        out.append("export_body_truncated=").append(cut).append('\n');
        out.append(clip(safe,MAX_BODY_BYTES,"\n[crash evidence body truncated]\n")).append('\n');
        return out.toString();
    }
    private static String preference(String value){return value.equals("on")||value.equals("off")?value:"unknown";}

    private static Association associate(Map<String,String> h,JSONObject state,String packageName,long now,long elapsed) {
        if(!packageName.equals(h.get("package")))return excluded("other_package");
        String process=h.get("process");
        if(process==null || !(process.equals(packageName)||process.startsWith(packageName+":")))return excluded("other_process");
        long pid=number(h,"pid"),at=timestamp(h.get("timestamp_utc"));
        if(pid<=0||pid>Integer.MAX_VALUE||at<0)return excluded("malformed_identity");
        if(at<=state.optLong("cleared_through"))return excluded("cleared_record");
        if(at>now)return excluded("future_record");
        String trial=h.get("recovery_trial_id");boolean sameTrial=trial!=null&&!trial.isEmpty()&&trial.equals(state.optString("trial"));
        if(trial!=null&&!trial.isEmpty()&&!sameTrial)return excluded("other_trial");
        if(!state.has("boot_epoch") || Math.abs((now-elapsed)-state.optLong("boot_epoch"))>CLOCK_TOLERANCE)
            return excluded("boot_or_clock_unverified");
        boolean hasBoot=h.containsKey("boot_epoch_ms"),hasElapsed=h.containsKey("captured_elapsed_ms"),hasStart=h.containsKey("process_started_elapsed_ms");
        long boot=number(h,"boot_epoch_ms"),captured=number(h,"captured_elapsed_ms"),started=number(h,"process_started_elapsed_ms");
        boolean fullIdentity=hasBoot&&hasElapsed&&hasStart&&boot>=0&&captured>=0&&started>=0;
        if(hasBoot && (boot<0||Math.abs(boot-state.optLong("boot_epoch"))>CLOCK_TOLERANCE))return excluded("boot_or_clock_mismatch");
        if(hasElapsed && (captured<0||captured>elapsed))return excluded("elapsed_time_mismatch");
        if(fullIdentity && (started>captured || Math.abs((boot+captured)-at)>CLOCK_TOLERANCE))return excluded("inconsistent_identity");
        JSONArray sessions=state.optJSONArray("sessions");
        if(sessions!=null)for(int i=0;i<sessions.length();i++){
            JSONObject session=sessions.optJSONObject(i);if(session==null||session.optLong("pid")!=pid||!process.equals(packageName))continue;
            long next=Long.MAX_VALUE;
            JSONObject following=sessions.optJSONObject(i+1);
            if(following!=null)next=following.optLong("at",Long.MAX_VALUE);
            if(fullIdentity && session.optLong("process_started_elapsed_ms",-1)==started){
                if(at>=next)return excluded("after_tracked_session");
                String status=at>state.optLong("started")+RecoveryCapture.LEASE_MS?"matched_process_after_collection_window":"matched_process_session";
                return new Association(status,session,true);
            }
            if(!hasBoot&&!hasElapsed&&!hasStart && at>=session.optLong("at") && at<next)
                return new Association("pid_and_wall_time_only",session,true);
        }
        // Preserve a new trial's early/subprocess error without inventing a main-process match.
        if(fullIdentity&&sameTrial)return new Association(process.equals(packageName)?"same_trial_untracked_process":"same_trial_secondary_process",null,true);
        return excluded(fullIdentity?"process_start_unmatched":"unmatched_legacy_record");
    }

    private static Map<String,String> fields(String text) {
        int stop=text.indexOf("\n\n[");if(stop<0)return null;
        Map<String,String> fields=new LinkedHashMap<>();
        for(String line:text.substring(0,stop).split("\n")){
            int colon=line.indexOf(':');if(colon<=0)continue;
            String key=line.substring(0,colon).trim(),value=line.substring(colon+1).trim();
            if(fields.put(key,value)!=null)return null;
        }
        return fields;
    }
    private static long number(Map<String,String> h,String key){try{return Long.parseLong(h.get(key));}catch(Exception bad){return -1;}}
    private static long timestamp(String value) {
        if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z"))return -1;
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));format.setLenient(false);
        ParsePosition pos=new ParsePosition(0);Date date=format.parse(value,pos);
        return date!=null&&pos.getIndex()==value.length()?date.getTime():-1;
    }
    static String clip(String text,int bytes,String marker) {
        byte[] raw=text.getBytes(StandardCharsets.UTF_8);if(raw.length<=bytes)return text;
        int end=Math.max(0,bytes-marker.getBytes(StandardCharsets.UTF_8).length);
        while(end>0&&(raw[end]&0xc0)==0x80)end--;
        return new String(raw,0,end,StandardCharsets.UTF_8)+marker;
    }
}
