package com.dsh.miuix.cardface.core.catalog

/**
 * 「二次元 IP」分类使用的 IP 名称集合：从 IP 联动目录里挑出动画 / 漫画 / 游戏 / 虚拟主播类 IP。
 *
 * 目录里的 ipName 不提供题材字段，这里按常见 IP 名称 + 关键词匹配；后续可以随时补充。
 * 同一张卡仍会出现在「IP 联动」里，这个分类只是复制一份方便挑。
 */
object AnimeIps {

    private val exact = setOf(
        "初音未来", "世界计划：无法歌唱的初音未来", "BanG Dream!", "偶像梦幻祭2",
        "未定事件簿", "光与夜之恋", "奇迹暖暖", "恋与深空", "命运-冠位指定",
        "明日方舟", "少女前线2：追放", "蔚蓝档案", "碧蓝航线", "鸣潮",
        "重返未来：1999", "尘白禁区", "幻塔", "少女乐队的呐喊", "迷宫饭", "文豪野犬",
        "名侦探柯南", "龙珠Z", "机动战士高达",
        "天官赐福动画", "狐妖小红娘", "赛尔号", "东方Project·不可思议的幻想乡",
        "沧元图", "牧神记", "人鱼陷落", "我家大师兄脑子有坑", "凸变英雄X",
        "雄狮少年2", "你的颜色", "海虎", "火凤燎原", "长歌行",
        "喜羊羊与灰太狼", "加菲猫", "姆明", "米菲miffy", "拓麻歌子",
        "星瞳", "真白花音", "美月もも", "早稻叽", "艾露露Ailurus",
        "真红莉爱", "艾尔莎_Channel", "2233", "黑色信标", "新月同行",
        "无期迷途", "胜利女神：新的希望", "花亦山心之月", "忘川风华录",
        "剑与远征:启程", "冲呀！饼干人：王国", "P5X", "MUSEDASH",
        "转生大作战", "不/存在的你，和我", "梦乐园", "奥比岛", "艾丝乐小兔",
        "BanG Dream！", "少女前线", "碧蓝档案",
    )

    private val keywords = listOf(
        "初音", "碧蓝", "蔚蓝", "少女前线", "少前", "追放", "明日方舟", "崩坏", "原神", "鸣潮",
        "幻塔", "尘白", "无期迷途", "重返未来", "恋与", "未定事件簿", "偶像梦幻祭",
        "光与夜", "奇迹暖暖", "命运-冠位", "FGO", "高达", "战双", "深空之眼", "阴阳师",
        "第七史诗", "白荆回廊", "天气之子", "你的颜色", "剑与远征", "饼干人",
        "哔哩哔哩", "乐正绫", "洛天依", "言和", "墨清弦", "伞少女梦谈", "龙族",
        "未来有你", "茈凌", "bangdream", "BanG",
        "名侦探柯南", "龙珠", "迷宫饭", "文豪野犬", "天官赐福", "狐妖",
        "沧元图", "牧神记", "长歌行", "火凤燎原", "海虎", "凸变英雄",
        "雄狮少年", "你的颜色", "赛马娘", "孤独摇滚", "芙莉莲", "间谍过家家",
        "咒术回战", "鬼灭", "排球少年", "我推的孩子", "转生", "异世界",
        "VOCALOID", "Project SEKAI", "虚拟主播", "VTuber",
    )

    fun isAnime(text: String): Boolean {
        val name = text.trim()
        if (name.isEmpty()) return false
        if (name in exact) return true
        return keywords.any { name.contains(it, ignoreCase = true) }
    }

    /** 交通卡标题是「碧蓝档案-ユウカ」这种，归组时还原成 IP 名。 */
    fun groupFor(title: String): String {
        val rules = listOf(
            "碧蓝档案" to listOf("碧蓝档案", "蔚蓝档案"),
            "碧蓝航线" to listOf("碧蓝航线"),
            "少女前线" to listOf("少前", "追放"),
            "初音未来" to listOf("初音", "未来有你"),
            "BanG Dream!" to listOf("bangdream", "BanG"),
            "白荆回廊" to listOf("白荆回廊"),
            "天气之子" to listOf("天气之子"),
            "重返未来" to listOf("重返未来"),
            "尘白禁区" to listOf("尘白"),
            "你的颜色" to listOf("你的颜色"),
            "剑与远征" to listOf("剑与远征"),
            "哔哩哔哩" to listOf("哔哩哔哩"),
            "崩坏" to listOf("崩坏"),
            "冲呀！饼干人" to listOf("饼干人"),
            "乐正绫" to listOf("乐正绫"),
            "洛天依" to listOf("洛天依"),
            "言和" to listOf("言和"),
            "墨清弦" to listOf("墨清弦"),
            "伞少女梦谈" to listOf("伞少女梦谈"),
            "深空之眼" to listOf("深空之眼"),
            "阴阳师" to listOf("阴阳师"),
            "龙族" to listOf("龙族"),
            "幻塔" to listOf("幻塔"),
            "战双帕弥什" to listOf("战双"),
            "第七史诗" to listOf("第七史诗"),
            "鸣潮" to listOf("鸣潮"),
            "茈凌" to listOf("茈凌"),
        )
        rules.forEach { (label, keys) ->
            if (keys.any { title.contains(it, ignoreCase = true) }) return label
        }
        return title
    }
}
