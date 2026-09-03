package org.quwuting.quwutingservice.emoji;

import java.util.ArrayList;
import java.util.List;

/**
 * 常见 Emoji 表情共享目录（门店 Reaction 与舞伴认可共用的<b>单一事实源</b>，2026-08-24 建立）。
 * <p>
 * <b>定位回归（根因分析，详见前后端 AGENTS.md「Reaction 表情」章节）</b>：Reaction 是
 * Telegram 式<b>情感表达媒介</b>，其价值随词汇广度与熟悉度增长；历史上按"决策信号价值/
 * 使用率"标准反复收缩字典（18→14→10→12 的折腾）是语义定位错误——决策信号本应由
 * 1-10 评分维度承担。本次将字典扩展到全部常见 Emoji（本目录 152 项，涵盖
 * 表情/手势/爱心/庆祝/动植物/食物/天气/物品），并启用长期闲置的 NEUTRAL 极性档位。
 * <p>
 * <b>2026-09-03 去噪收敛（用户驱动，定向删除 18 项 → 134 项）</b>：全放开不等同于无门槛——目录里
 * 与舞厅/舞伴评价场景<b>零关联的日常物象</b>（天气/自然 7 项 ☀️🌙🌈☁️❄️☔⚡、数码/办公/
 * 交通/运动 11 项 ⌚📱💻📷🎬🎨📚✈️🚗⚽🏀）不具备任何情感表达价值，纯占 Picker 屏位，
 * 定向移除（非 08-12 式按"使用率/决策信号"收缩，情绪/人物/庆祝类一字未动）。
 * 保留判断标准 = 「能否在舞厅/舞伴场景想象出点击表达」：人物/情绪/动物/花卉/酒水保留，
 * 场景外纯物象删除。删除为纯字典操作：toggle 入口 isValid 拦截、徽标构建对枚举外
 * code 优雅忽略（buildTopBadgesFromCounts），历史已点击数据零报错仅不再展示。
 * <p>
 * <b>职责边界（防重蹈覆辙）</b>：
 * <ul>
 *   <li>本目录 = 常见表情层，label 一律取 Unicode CLDR 中文短名（咧嘴笑/热狗/赞…），
 *       description 为黄脸/物象描述——<b>不承担舞厅业务语义</b>。业务信号（人气旺/
 *       舞伴充足/收费偏高…）由各域枚举的 legacy code 承担（语义已被历史验证，
 *       禁止再给新表情塞业务文案——08-12 收缩的教训是"业务信号与情感表达混层"）。</li>
 *   <li>code 命名系统性 = {@code EMOJI_<HEX>}（unicode hex 大写），新增表情零命名负担、
 *       可从 emoji 字符确定性推导；legacy 语义 code（HOT/RECOMMEND…）保留在域枚举。</li>
 *   <li>极性（{@link Polarity}）为<b>词典级属性</b>：明显正向（笑/爱/庆祝/称赞）→ POSITIVE，
 *       明显负向（怒/哭/病/拒绝）→ NEGATIVE，无法判明正负（中性脸/食物/动物/物品…）→ NEUTRAL。
 *       热度公式只计 POSITIVE、负面单独计数、NEUTRAL 仅展示不入公式（见 ReactionCode 适配器）。</li>
 *   <li>渲染一律系统 emoji 文本（前端 <text>），无图片资源——零包体积、全设备覆盖、
 *       新增表情零资源摩擦（OpenMoji/Q 版图管线是历史上"扩字典成本 &gt; 收缩成本"的最大驱动）。</li>
 * </ul>
 * <p>
 * <b>双端同步配方（改本目录必须同步）</b>：后端本枚举 + 前端
 * {@code miniprogram/constants/emoji-catalog.ts} + 前端类型
 * {@code miniprogram/types/emoji.ts}（EmojiReactionCode 联合）——三处内容一致；
 * 域枚举（ReactionCode / DancerTagCode）与前端域字典自动适配，无需逐项手改。
 * <p>
 * emoji 源文件为 UTF-8 明文（Spring Boot parent 强制 UTF-8 编译），严禁手写反斜杠
 * 加 u 的 unicode 转义序列（Java 注释中的此类序列也会被预处理器解析，会编译失败）。
 */
public enum EmojiCatalog {

    // ── 表情与情感（Smileys & Emotion） ────────────────────────────────────────
    EMOJI_1F600("😀", "咧嘴笑", "咧嘴大笑的脸", Polarity.POSITIVE),
    EMOJI_1F601("😁", "露齿笑", "露齿大笑的脸", Polarity.POSITIVE),
    EMOJI_1F602("😂", "笑哭", "笑哭的表情", Polarity.POSITIVE),
    EMOJI_1F923("🤣", "笑死", "笑得满地打滚", Polarity.POSITIVE),
    EMOJI_1F60A("😊", "微笑", "眯眼微笑的脸", Polarity.POSITIVE),
    EMOJI_1F60D("😍", "花痴", "爱心眼，很喜欢", Polarity.POSITIVE),
    EMOJI_1F970("🥰", "爱慕", "满脸爱意的脸", Polarity.POSITIVE),
    EMOJI_1F618("😘", "飞吻", "飞吻的表情", Polarity.POSITIVE),
    EMOJI_1F60B("😋", "美味", "看到好吃的流口水", Polarity.POSITIVE),
    EMOJI_1F61B("😛", "吐舌", "吐舌头的调皮脸", Polarity.POSITIVE),
    EMOJI_1F61C("😜", "调皮", "挤眉弄眼的调皮脸", Polarity.POSITIVE),
    EMOJI_1F61D("😝", "眯眼吐舌", "眯眼吐舌的调皮脸", Polarity.POSITIVE),
    EMOJI_1F92A("🤪", "疯癫笑", "疯疯癫癫的笑脸", Polarity.POSITIVE),
    EMOJI_1F929("🤩", "星星眼", "崇拜的星星眼", Polarity.POSITIVE),
    EMOJI_1F973("🥳", "庆祝", "派对帽庆祝脸", Polarity.POSITIVE),
    EMOJI_1F60E("😎", "墨镜脸", "戴墨镜的酷脸", Polarity.POSITIVE),
    EMOJI_1F913("🤓", "书呆子", "戴眼镜的书呆子脸", Polarity.POSITIVE),
    EMOJI_1F914("🤔", "思考", "思考的表情", Polarity.NEUTRAL),
    EMOJI_1F928("🤨", "挑眉", "挑眉怀疑的表情", Polarity.NEGATIVE),
    EMOJI_1F610("😐", "面无表情", "面无表情的脸", Polarity.NEUTRAL),
    EMOJI_1F611("😑", "无语", "无语的表情", Polarity.NEUTRAL),
    EMOJI_1F60F("😏", "得意", "得意的坏笑", Polarity.POSITIVE),
    EMOJI_1F612("😒", "不满", "不满的表情", Polarity.NEGATIVE),
    EMOJI_1F644("🙄", "翻白眼", "翻白眼的表情", Polarity.NEGATIVE),
    EMOJI_1F62C("😬", "呲牙咧嘴", "呲牙咧嘴的尴尬脸", Polarity.NEGATIVE),
    EMOJI_1F62E("😮", "吃惊", "惊讶张着嘴", Polarity.NEUTRAL),
    EMOJI_1F632("😲", "震惊", "震惊的表情", Polarity.NEUTRAL),
    EMOJI_1F633("😳", "脸红", "脸红发烫的表情", Polarity.NEUTRAL),
    EMOJI_1F97A("🥺", "委屈", "委屈巴巴的可怜脸", Polarity.NEUTRAL),
    EMOJI_1F622("😢", "大哭", "流眼泪的表情", Polarity.NEGATIVE),
    EMOJI_1F62D("😭", "嚎啕大哭", "嚎啕大哭的表情", Polarity.NEGATIVE),
    EMOJI_1F624("😤", "忍住怒气", "憋着气的表情", Polarity.NEGATIVE),
    EMOJI_1F620("😠", "生气", "生气的表情", Polarity.NEGATIVE),
    EMOJI_1F621("😡", "发怒", "发怒通红的脸", Polarity.NEGATIVE),
    EMOJI_1F92C("🤬", "满嘴脏话", "骂脏话的表情", Polarity.NEGATIVE),
    EMOJI_1F92F("🤯", "头爆炸", "震惊到头脑爆炸", Polarity.NEUTRAL),
    EMOJI_1F631("😱", "吓死", "吓得尖叫的表情", Polarity.NEGATIVE),
    EMOJI_1F628("😨", "恐惧", "惊恐的表情", Polarity.NEGATIVE),
    EMOJI_1F625("😥", "松口气", "如释重负又难过的脸", Polarity.NEGATIVE),
    EMOJI_1F613("😓", "冷汗", "冒冷汗的表情", Polarity.NEGATIVE),
    EMOJI_1F917("🤗", "拥抱", "张开手要拥抱", Polarity.POSITIVE),
    EMOJI_1F92D("🤭", "捂嘴笑", "捂嘴偷笑", Polarity.POSITIVE),
    EMOJI_1F92B("🤫", "嘘声", "嘘，别说话", Polarity.NEUTRAL),
    EMOJI_1F637("😷", "戴口罩", "戴着口罩的脸", Polarity.NEGATIVE),
    EMOJI_1F912("🤒", "发烧", "发烧难受的脸", Polarity.NEGATIVE),
    EMOJI_1F915("🤕", "头受伤", "头缠绷带的脸", Polarity.NEGATIVE),
    EMOJI_1F922("🤢", "恶心", "恶心反胃的表情", Polarity.NEGATIVE),
    EMOJI_1F92E("🤮", "呕吐", "呕吐的表情", Polarity.NEGATIVE),
    EMOJI_1F927("🤧", "打喷嚏", "打喷嚏的表情", Polarity.NEGATIVE),
    EMOJI_1F975("🥵", "热", "热得冒汗的脸", Polarity.NEGATIVE),
    EMOJI_1F976("🥶", "冷", "冻得发抖的脸", Polarity.NEGATIVE),
    EMOJI_1F635("😵", "头晕", "头晕眼花的表情", Polarity.NEUTRAL),
    EMOJI_1F910("🤐", "闭嘴", "拉链封嘴", Polarity.NEUTRAL),
    EMOJI_1F634("😴", "睡觉", "睡觉的脸", Polarity.NEUTRAL),
    EMOJI_1F608("😈", "恶魔脸", "坏笑的恶魔脸", Polarity.NEGATIVE),
    EMOJI_1F4A9("💩", "便便", "一坨便便", Polarity.NEUTRAL),
    EMOJI_1F47B("👻", "幽灵", "白色小幽灵", Polarity.NEUTRAL),

    // ── 手势（Hand Gestures） ──────────────────────────────────────────────────
    EMOJI_1F44D("👍", "赞", "竖大拇指，表示赞", Polarity.POSITIVE),
    EMOJI_1F44E("👎", "踩", "竖大拇指朝下，表示差", Polarity.NEGATIVE),
    EMOJI_1F44C("👌", "好的", "OK 手势，表示没问题", Polarity.POSITIVE),
    EMOJI_270C("✌️", "胜利手势", "比 V 字胜利手势", Polarity.POSITIVE),
    EMOJI_1F91E("🤞", "祈祷好运", "交叉手指，求好运", Polarity.POSITIVE),
    EMOJI_1F919("🤙", "给我打电话", "打电话手势", Polarity.NEUTRAL),
    EMOJI_1F44B("👋", "挥手", "挥手打招呼", Polarity.NEUTRAL),
    EMOJI_1F91D("🤝", "握手", "握手的双手", Polarity.POSITIVE),
    EMOJI_1F64F("🙏", "双手合十", "双手合十，表示感谢或祈祷", Polarity.POSITIVE),
    EMOJI_270A("✊", "拳头", "握紧的拳头", Polarity.POSITIVE),
    EMOJI_1F44A("👊", "出拳", "挥出的拳头", Polarity.POSITIVE),
    EMOJI_1F44F("👏", "鼓掌", "鼓掌的双手", Polarity.POSITIVE),
    EMOJI_1F64C("🙌", "举手欢呼", "举起双手欢呼", Polarity.POSITIVE),
    EMOJI_1F4AA("💪", "肌肉", "秀肌肉，加油", Polarity.POSITIVE),

    // ── 爱心（Hearts） ────────────────────────────────────────────────────────
    EMOJI_2764("❤️", "红心", "红心，代表喜欢", Polarity.POSITIVE),
    EMOJI_1F9E1("🧡", "橙心", "橙色的心", Polarity.POSITIVE),
    EMOJI_1F49B("💛", "黄心", "黄色的心", Polarity.POSITIVE),
    EMOJI_1F49A("💚", "绿心", "绿色的心", Polarity.POSITIVE),
    EMOJI_1F499("💙", "蓝心", "蓝色的心", Polarity.POSITIVE),
    EMOJI_1F49C("💜", "紫心", "紫色的心", Polarity.POSITIVE),
    EMOJI_1F5A4("🖤", "黑心", "黑色的心", Polarity.POSITIVE),
    EMOJI_1F90D("🤍", "白心", "白色的心", Polarity.POSITIVE),
    EMOJI_1F494("💔", "心碎", "破碎的心", Polarity.NEGATIVE),
    EMOJI_1F495("💕", "两颗心", "两颗粉色的心", Polarity.POSITIVE),
    EMOJI_1F497("💗", "心动", "跳动的粉色心", Polarity.POSITIVE),
    EMOJI_1F498("💘", "心形箭头", "被箭射中的心", Polarity.POSITIVE),

    // ── 庆祝与符号（Celebration & Symbols） ───────────────────────────────────
    EMOJI_2728("✨", "闪亮", "闪闪发光", Polarity.POSITIVE),
    EMOJI_2B50("⭐", "星星", "五角星", Polarity.POSITIVE),
    EMOJI_1F31F("🌟", "闪光星", "闪闪发光的星星", Polarity.POSITIVE),
    EMOJI_1F4AB("💫", "头晕眼花", "旋转的星星，晕", Polarity.NEUTRAL),
    EMOJI_1F4A5("💥", "碰撞", "碰撞爆炸的效果", Polarity.NEUTRAL),
    EMOJI_1F525("🔥", "火", "一团火焰", Polarity.POSITIVE),
    EMOJI_1F4A1("💡", "灯泡", "亮起的灯泡，有主意了", Polarity.POSITIVE),
    EMOJI_1F389("🎉", "派对彩带", "拉响的派对彩带", Polarity.POSITIVE),
    EMOJI_1F38A("🎊", "五彩纸屑", "五彩纸屑球", Polarity.POSITIVE),
    EMOJI_1F381("🎁", "礼物", "系着蝴蝶结的礼物盒", Polarity.POSITIVE),
    EMOJI_1F382("🎂", "生日蛋糕", "点着蜡烛的生日蛋糕", Polarity.POSITIVE),
    EMOJI_1F3B5("🎵", "音符", "一个音符", Polarity.NEUTRAL),
    EMOJI_1F3B6("🎶", "音乐", "连排的音符", Polarity.NEUTRAL),
    EMOJI_1F680("🚀", "火箭", "火箭，一飞冲天", Polarity.POSITIVE),
    EMOJI_1F3C6("🏆", "奖杯", "冠军奖杯", Polarity.POSITIVE),
    EMOJI_1F3AF("🎯", "靶心", "正中靶心", Polarity.POSITIVE),
    EMOJI_1F483("💃", "跳舞", "跳舞的女人", Polarity.NEUTRAL),

    // ── 动物（Animals） ───────────────────────────────────────────────────────
    EMOJI_1F436("🐶", "狗脸", "小狗的脸", Polarity.NEUTRAL),
    EMOJI_1F431("🐱", "猫脸", "小猫的脸", Polarity.NEUTRAL),
    EMOJI_1F430("🐰", "兔脸", "小兔子的脸", Polarity.NEUTRAL),
    EMOJI_1F43C("🐼", "熊猫", "可爱的熊猫", Polarity.NEUTRAL),
    EMOJI_1F437("🐷", "猪脸", "小猪的脸", Polarity.NEUTRAL),
    EMOJI_1F438("🐸", "青蛙", "青蛙的脸", Polarity.NEUTRAL),
    EMOJI_1F435("🐵", "猴脸", "猴子的脸", Polarity.NEUTRAL),
    EMOJI_1F984("🦄", "独角兽", "独角兽", Polarity.NEUTRAL),
    EMOJI_1F42F("🐯", "虎脸", "老虎的脸", Polarity.NEUTRAL),
    EMOJI_1F98A("🦊", "狐狸", "狐狸的脸", Polarity.NEUTRAL),

    // ── 植物（Plants） ────────────────────────────────────────────────────────
    EMOJI_1F338("🌸", "樱花", "盛开的樱花", Polarity.NEUTRAL),
    EMOJI_1F339("🌹", "玫瑰", "红玫瑰", Polarity.NEUTRAL),
    EMOJI_1F33B("🌻", "向日葵", "向日葵", Polarity.NEUTRAL),
    EMOJI_1F337("🌷", "郁金香", "郁金香", Polarity.NEUTRAL),
    EMOJI_1F340("🍀", "四叶草", "四叶草，好运", Polarity.POSITIVE),
    EMOJI_1F331("🌱", "幼苗", "刚发芽的幼苗", Polarity.POSITIVE),
    EMOJI_1F333("🌳", "树", "一棵大树", Polarity.NEUTRAL),

    // ── 食物（Food） ──────────────────────────────────────────────────────────
    EMOJI_1F34E("🍎", "苹果", "红苹果", Polarity.NEUTRAL),
    EMOJI_1F349("🍉", "西瓜", "一瓣西瓜", Polarity.NEUTRAL),
    EMOJI_1F353("🍓", "草莓", "草莓", Polarity.NEUTRAL),
    EMOJI_1F347("🍇", "葡萄", "一串葡萄", Polarity.NEUTRAL),
    EMOJI_1F354("🍔", "汉堡", "汉堡包", Polarity.NEUTRAL),
    EMOJI_1F355("🍕", "披萨", "一块披萨", Polarity.NEUTRAL),
    EMOJI_1F32D("🌭", "热狗", "热狗", Polarity.NEUTRAL),
    EMOJI_1F35F("🍟", "薯条", "一盒薯条", Polarity.NEUTRAL),
    EMOJI_1F370("🍰", "蛋糕", "一块奶油蛋糕", Polarity.NEUTRAL),
    EMOJI_1F36B("🍫", "巧克力", "巧克力", Polarity.NEUTRAL),
    EMOJI_1F37A("🍺", "啤酒", "一大杯啤酒", Polarity.NEUTRAL),
    EMOJI_1F37B("🍻", "干杯", "碰杯的啤酒", Polarity.NEUTRAL),
    EMOJI_2615("☕", "咖啡", "一杯热咖啡", Polarity.NEUTRAL),
    EMOJI_1F363("🍣", "寿司", "寿司", Polarity.NEUTRAL),

    // ── 物品（Objects） ──────────────────────────────────────────────────────
    EMOJI_1F4B0("💰", "钱袋", "装满钱的钱袋", Polarity.NEUTRAL),
    EMOJI_1F48E("💎", "宝石", "闪闪发光的宝石", Polarity.POSITIVE),
    EMOJI_1F451("👑", "王冠", "王冠", Polarity.POSITIVE);

    /** Emoji 极性：热度公式只计入 POSITIVE；NEGATIVE 单独计数展示；NEUTRAL 仅展示不入公式 */
    public enum Polarity {
        POSITIVE, NEUTRAL, NEGATIVE
    }

    private final String emoji;
    private final String label;
    private final String description;
    private final Polarity polarity;

    EmojiCatalog(String emoji, String label, String description, Polarity polarity) {
        this.emoji = emoji;
        this.label = label;
        this.description = description;
        this.polarity = polarity;
    }

    /** 展示用 emoji 字符（前端 <text> 渲染；后端 stats/徽标下发契约） */
    public String getEmoji() {
        return emoji;
    }

    /** 中文名（CLDR 中文短名；非业务文案） */
    public String getLabel() {
        return label;
    }

    /** 黄脸/物象描述（前端长按说明卡与表情说明页展示） */
    public String getDescription() {
        return description;
    }

    /** 词典级极性（见类注释：明显正→POSITIVE / 明显负→NEGATIVE / 无法判明→NEUTRAL） */
    public Polarity getPolarity() {
        return polarity;
    }

    /** code 名 = {@code EMOJI_<HEX>}（确定性派生，禁手写——新增条目照抄枚举名即可） */
    public String code() {
        return name();
    }

    /** 目录全部 code（声明顺序 = Picker 展示序；域枚举适配器在 legacy 之后追加本列表） */
    public static List<String> allCodes() {
        List<String> codes = new ArrayList<>(values().length);
        for (EmojiCatalog value : values()) {
            codes.add(value.name());
        }
        return codes;
    }

    /** 校验字符串是否为合法目录 code（避免 valueOf 抛未受控异常） */
    public static boolean isValid(String code) {
        if (code == null) return false;
        for (EmojiCatalog value : values()) {
            if (value.name().equals(code)) return true;
        }
        return false;
    }

    /** 目录内 emoji 查询（非法 code 返回 null；调用方须先 isValid） */
    public static String emojiOf(String code) {
        EmojiCatalog e = find(code);
        return e != null ? e.getEmoji() : null;
    }

    /** 目录内 label 查询（非法 code 返回 null；调用方须先 isValid） */
    public static String labelOf(String code) {
        EmojiCatalog e = find(code);
        return e != null ? e.getLabel() : null;
    }

    /** 目录内 description 查询（非法 code 返回 null；调用方须先 isValid） */
    public static String descriptionOf(String code) {
        EmojiCatalog e = find(code);
        return e != null ? e.getDescription() : null;
    }

    /** 目录内极性查询（非法 code 返回 null；调用方须先 isValid） */
    public static Polarity polarityOf(String code) {
        EmojiCatalog e = find(code);
        return e != null ? e.getPolarity() : null;
    }

    /** 目录内指定极性 code 列表（域枚举极性过滤的唯一入口） */
    public static List<String> codesOfPolarity(Polarity target) {
        List<String> codes = new ArrayList<>();
        for (EmojiCatalog value : values()) {
            if (value.polarity == target) {
                codes.add(value.name());
            }
        }
        return codes;
    }

    private static EmojiCatalog find(String code) {
        if (code == null) return null;
        for (EmojiCatalog value : values()) {
            if (value.name().equals(code)) return value;
        }
        return null;
    }
}
