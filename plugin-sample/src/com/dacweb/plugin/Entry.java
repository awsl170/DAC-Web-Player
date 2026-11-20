package com.dacweb.plugin;

import com.dacweb.player.plugin.MusicPlugin;
import com.dacweb.player.plugin.PluginStream;
import com.dacweb.player.plugin.PluginTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小插件示例（纯 Java，不需要 Kotlin 运行时）。
 *
 * 把它改成你自己的实现：
 *   1. search() 里调你的搜索接口，把结果映射成 PluginTrack（extra 里随便塞需要带回的数据）；
 *   2. resolve() 里用 track.extra 里的信息换出播放直链；
 *   3. 可选实现 lyrics() 返回 LRC 文本；
 *   4. 只做封面/歌词的插件把 getProvidesSearch() 返回 false，宿主就不会拿它去搜歌。
 *
 * 打包：bash build-sample.sh → myprovider.jar，然后在 app 里「设置 → 音源插件 → 导入」。
 */
public class Entry implements MusicPlugin {

    /** 宿主会优先读这个字段（Kotlin object 的 INSTANCE 也是这个形式）。 */
    public static final Entry INSTANCE = new Entry();

    @Override
    public String getId() {
        return "com.dacweb.plugin.sample";
    }

    @Override
    public String getName() {
        return "示例插件（本地演示）";
    }

    @Override
    public String getVersion() {
        return "0.1";
    }

    /** 本示例是音源插件：宿主搜索时会问它。 */
    @Override
    public boolean getProvidesSearch() {
        return true;
    }

    /** 同时也能补元数据（本示例没实现 enrich/cover，实际形同关闭）。 */
    @Override
    public boolean getProvidesMetadata() {
        return false;
    }

    /** 声明优先的元数据源，只在宿主使用内置元数据插件时生效。 */
    @Override
    public List<String> preferredMetadata() {
        List<String> out = new ArrayList<>();
        out.add("netease");
        return out;
    }

    @Override
    public String getDescription() {
        return "演示插件：返回两条假数据，直链指向一个公开测试音频。用来验证导入/搜索/播放链路。";
    }

    @Override
    public List<PluginTrack> search(String keyword, int page, int size, String quality) {
        List<PluginTrack> out = new ArrayList<>();
        if (page > 1) return out;   // 只有一页演示数据

        Map<String, String> extra = new HashMap<>();
        extra.put("demo", "1");
        out.add(new PluginTrack(
                "demo-1",                       // id
                "示例曲目 · " + keyword,        // 名称
                "DAC Web Player",               // 歌手
                "插件演示",                     // 专辑
                "",                             // 封面
                0L,                             // 时长 ms
                quality,                        // 原样回传
                extra
        ));
        out.add(new PluginTrack(
                "demo-2", "示例曲目二 · " + keyword, "DAC Web Player", "插件演示",
                "", 0L, quality, extra
        ));
        return out;
    }

    @Override
    public PluginStream resolve(PluginTrack track, String quality) {
        // 演示用：一个公开的测试音频（能联网就能播）。换成你自己的接口即可。
        String url = "https://download.samplelib.com/mp3/sample-15s.mp3";
        return new PluginStream(
                url,
                track.getName(),
                track.getArtist(),
                track.getAlbum(),
                track.getCover(),
                "示例 · MP3",
                "mp3",
                ""
        );
    }

    @Override
    public String lyrics(String name, String artist) {
        return "[00:00.00]示例歌词\n[00:02.00]由插件提供\n";
    }
}
