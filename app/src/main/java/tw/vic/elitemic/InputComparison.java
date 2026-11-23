package tw.vic.elitemic;

import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;

final class InputComparison {
    static final String[] TYPES={"3.5 mm 耳麥","傳統藍牙 SCO","藍牙 LE 耳麥","USB 耳麥","USB 音訊裝置","手機內建麥克風"};
    private static final String MIXED="多個外接輸入同時列出";
    private static final String BLUETOOTH_WITH_RECEIVER="藍牙已連且列出有線／USB 輸入";
    static void observe(String entry, Map<String,String> result) {
        int inputs=entry.indexOf("｜列出的輸入 "), recording=entry.indexOf("｜錄音 ");
        if (inputs<0 || recording<inputs) return;
        String recorded=entry.substring(recording+"｜錄音 ".length());
        if (recorded.equals("沒有可見錄音") || recorded.equals("未知")) return;
        String available=entry.substring(inputs+"｜列出的輸入 ".length(),recording);
        for(String device:TYPES)
            for(String part:recorded.split("、"))
                if(part.startsWith(device+"（")) result.put(device,entry);
        int externalTypes=0;
        for (int i=0;i<TYPES.length-1;i++)
            if(java.util.Arrays.asList(available.split("、")).contains(TYPES[i])) externalTypes++;
        if(externalTypes>=2) result.put(MIXED,entry);
        String profiles=entry.substring(0,inputs);
        if ((profiles.contains("HFP 連線｜") || profiles.contains("A2DP 連線｜")) &&
                (available.contains("USB 耳麥") || available.contains("USB 音訊裝置") || available.contains("3.5 mm 耳麥")))
            result.put(BLUETOOTH_WITH_RECEIVER,entry);
    }
    static void remember(SharedPreferences.Editor edit, String entry) {
        Map<String,String> matches=new LinkedHashMap<>();
        observe(entry,matches);
        for(Map.Entry<String,String> m:matches.entrySet()) edit.putString("routeGroup."+m.getKey(),m.getValue());
    }
    // Replay old rows before they rotate out on the first service start after upgrading.
    static void migrate(SharedPreferences prefs) {
        if(prefs.contains("routeCompareMigrated")) return;
        String[] rows=prefs.getString("systemAudioHistory","").split("\n");
        SharedPreferences.Editor edit=prefs.edit();
        for(int i=rows.length-1;i>=0;i--) remember(edit,rows[i]);
        edit.putBoolean("routeCompareMigrated",true).apply();
    }
    static String report(SharedPreferences prefs) {
        migrate(prefs);
        StringBuilder text=new StringBuilder("各輸入最近一次可見錄音（全機）");
        for(String key:TYPES) append(text,prefs,key);
        append(text,prefs,MIXED);
        append(text,prefs,BLUETOOTH_WITH_RECEIVER);
        text.append("\n\n前景六秒實測（本工具）");
        boolean any=false;
        for(String key:TYPES) {
            String value=prefs.getString("level."+key,null);
            if(value!=null) { text.append("\n").append(value); any=true; }
        }
        if(!any) text.append("\n尚無獨立量測");
        String last=prefs.getString("lastLevelAttempt",null);
        if(last!=null) text.append("\n最近量測結果：").append(last);
        return text.toString();
    }
    private static void append(StringBuilder text,SharedPreferences prefs,String key) {
        String row=prefs.getString("routeGroup."+key,null);
        if(row!=null) text.append("\n").append(key).append("：").append(row);
    }
}
