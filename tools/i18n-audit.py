#!/usr/bin/env python3
"""Spelling and typography audit of the Russian strings (Android values-ru, string-array, plurals).

Needs: pip install language_tool_python (downloads LanguageTool, runs locally with Java; nothing is sent anywhere).
Prints one line per finding: key | rule | text | suggestion. Technical words are filtered by a small allow list.
"""
import re, sys, html
import language_tool_python

PATH = 'android/V2rayNG/app/src/main/res/values-ru/strings.xml'
ALLOW = {w.lower() for w in """
FlowVeil Xray sing-box VLESS VMess Trojan Hysteria Hysteria2 TUIC WireGuard Shadowsocks Reality XHTTP gRPC TUN DNS DoH IPv4 IPv6 UDP TCP
HTTP HTTPS SOCKS SOCKS5 QR Wi-Fi APK Telegram GitHub WebDAV MMKV JSON YAML Clash Mihomo base64 geoip geosite SNI ALPN uTLS mux
пинг пинга пингу пингом пинги пингов вайфай роутер роутера дефолт хост хоста хостов прокси хостинг лог логи логов логах ядро ядра
мбит гбит кбит мс кб мб гб тб фрагментация фрагментацию фрагментации реалити хеш хеша хэш автообход автообхода автообновление
автообновления автоподбор автоподбора автопереключение автоподключение автозапуск бэкап бэкапа v2rayN v2rayNG Happ HWID GUID
""".split()}

def strings(text):
    for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', text, re.S):
        yield m.group(1), m.group(2)
    for m in re.finditer(r'<(string-array|plurals) name="([^"]+)"[^>]*>(.*?)</\1>', text, re.S):
        for i, it in enumerate(re.findall(r'<item[^>]*>(.*?)</item>', m.group(3), re.S)):
            yield f'{m.group(2)}[{i}]', it

def clean(v):
    v = re.sub(r'<[^>]+>', '', v)
    v = html.unescape(v).replace("\\'", "'").replace('\\"', '"').replace('\\n', '\n')
    return re.sub(r'%(\d+\$)?[sd]', 'X', v)

def main():
    tool = language_tool_python.LanguageTool('ru-RU')
    text = open(PATH, encoding='utf-8').read()
    for key, raw in strings(text):
        v = clean(raw)
        if not re.search('[а-яё]', v, re.I):
            continue
        for m in tool.check(v):
            word = v[m.offset:m.offset + m.error_length]
            if word.lower() in ALLOW or m.rule_id in ('WHITESPACE_RULE', 'UPPERCASE_SENTENCE_START', 'COMMA_PARENTHESIS_WHITESPACE'):
                continue
            if re.fullmatch(r'[A-Za-z0-9._:/-]+', word):
                continue
            print(f'{key} | {m.rule_id} | {word!r} in {v[:90]!r} | {", ".join(m.replacements[:3])}')

if __name__ == '__main__':
    main()
