#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""进行中：N 从 toolName(岛右侧/subText) 移到展开副标题(槽位9)"""
R = '/root/rp_forge_clone/app/src/main/java/com/operit/rearprogress/'

# ============ A) AgentProgress.toolName()：去掉进行中拼接 ============
f = R + 'AgentProgress.java'
s = open(f, encoding='utf-8').read()

old = '''        String base = toolNameBase();
        // 多任务（>1 个对话进行中）→ 副标题右侧显示"进行中：N"；一个任务不显示（用户要求）
        try {
            int active = ChatCounter.activeCount();
            if (active > 1) {
                return base + " 进行中：" + active;
            }
        } catch (Throwable ignored) {}'''
assert old in s, 'toolName anchor'

new = '''        String base = toolNameBase();
        // 用户要求（2026-10-28）：进行中：N 不再拼进 toolName（它会进 subText 被 MIUI 渲染到岛右边），
        //   改由展开副标题（槽位9 subContent）显示，跨进程经广播 extra "active" 传递。'''
s = s.replace(old, new, 1)
open(f, 'w', encoding='utf-8').write(s)
print('AgentProgress OK')

# ============ B) FocusNotify：sActiveCount + 槽位9 ============
f = R + 'FocusNotify.java'
s = open(f, encoding='utf-8').read()

old = '''    static volatile String sSpinner = "";'''
assert old in s, 'sSpinner anchor'
s = s.replace(old, old + '\n    static volatile int sActiveCount = 0;   // 进行中对话数（跨进程广播传入，用于槽位9）', 1)

# forwardMirror 传 active
old = '''            i.putExtra("spinner", AgentProgress.spinner());'''
assert old in s, 'forwardMirror spinner anchor'
s = s.replace(old, old + '''
            i.putExtra("active", ChatCounter.activeCount());   // 进行中对话数→展开副标题''', 1)

# pushMirrorLocal 签名 +赋值（9参）
old = '''    static void pushMirrorLocal(String title, String toolName, String body, int percent) {
        pushMirrorLocal(title, toolName, body, percent, "", "", "", "");
    }'''
assert old in s, 'push4 anchor'
s = s.replace(old, '''    static void pushMirrorLocal(String title, String toolName, String body, int percent) {
        pushMirrorLocal(title, toolName, body, percent, "", "", "", "", 0);
    }''', 1)

old = '''                                String rate, String sparkline, String interval,
                                String spinner) {'''
assert old in s, 'push sig anchor'
s = s.replace(old, '''                                String rate, String sparkline, String interval,
                                String spinner, int active) {''', 1)

old = '''        sSpinner = spinner == null ? "" : spinner;'''
assert old in s, 'sSpinner set anchor'
s = s.replace(old, old + '\n        sActiveCount = active;', 1)

# 槽位9：token + 转轮 + 进行中：N
old = '''.put("subContent", SLOT_TEST ? "【9-焦点卡副内容】"
                            : (tok + (sSpinner.isEmpty() ? "" : " " + sSpinner)));'''
assert old in s, 'slot9 anchor'
s = s.replace(old, '''.put("subContent", SLOT_TEST ? "【9-焦点卡副内容】"
                            : (tok + (sSpinner.isEmpty() ? "" : " " + sSpinner)
                                    // 用户要求：进行中：N 显示在展开副标题（不再进岛右侧）
                                    + (sActiveCount > 1 ? " 进行中：" + sActiveCount : "")));''', 1)

open(f, 'w', encoding='utf-8').write(s)
print('FocusNotify OK')

# ============ C) MainHook 转发 active ============
f = R + 'MainHook.java'
s = open(f, encoding='utf-8').read()
old = '''                                    intent.getStringExtra("spinner"));'''
assert old in s, 'mainhook anchor'
s = s.replace(old, '''                                    intent.getStringExtra("spinner"),
                                    intent.getIntExtra("active", 0));''', 1)
open(f, 'w', encoding='utf-8').write(s)
print('MainHook OK')

g = '/root/rp_forge_clone/app/build.gradle.kts'
t = open(g, encoding='utf-8').read()
t = t.replace('versionCode = 119', 'versionCode = 120').replace('versionName = "1.7.2-dev"', 'versionName = "1.7.3-dev"')
open(g, 'w', encoding='utf-8').write(t)
print('bump 1.7.3-dev')