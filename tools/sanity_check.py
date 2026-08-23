#!/usr/bin/env python3
"""
Kotlin 소스의 흔한 사고를 컴파일 없이 미리 잡는다.

이 저장소는 안드로이드 SDK 없이 작업하는 경우가 있어서, 실수를 CI 까지
가서야 알게 된다. 그중 반복해서 났던 두 가지만 골라 검사한다.

  1) 컴포저블의 마지막 파라미터가 Modifier 인데 콜백도 함께 받는 경우
     -> 호출부의 후행 람다가 콜백이 아니라 modifier 에 붙어 컴파일이 깨진다
  2) 중괄호/괄호 짝이 맞지 않는 파일
     -> 스크립트로 코드를 자동 수정하다 잘라 먹었을 때 걸린다

  python3 tools/sanity_check.py
"""
import glob
import re
import sys

SRC = "app/src/main/java/**/*.kt"


def strip_code(text):
    """문자열·주석을 공백으로 지운 코드만 남긴다. 괄호 세기용."""
    out = []
    i, n = 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two == "//":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif two == "/*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif text[i:i + 3] == '"""':
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif text[i] == '"':
            i += 1
            depth = 0
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i:i + 2] == "${":
                    depth += 1
                    i += 2
                    continue
                if depth and text[i] == "}":
                    depth -= 1
                    i += 1
                    continue
                if text[i] == '"' and depth == 0:
                    i += 1
                    break
                i += 1
        elif text[i] == "'":
            i += 2 if text[i + 1:i + 2] != "\\" else 3
            if i < n and text[i] == "'":
                i += 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def check_balance(path, text):
    code = strip_code(text)
    problems = []
    for opener, closer, label in (("{", "}", "중괄호"), ("(", ")", "괄호")):
        diff = code.count(opener) - code.count(closer)
        if diff:
            more = "여는" if diff > 0 else "닫는"
            problems.append(f"{path}: {label} {more} 쪽이 {abs(diff)}개 많습니다")
    return problems


def split_params(params):
    """최상위 쉼표로만 나눈다. (Int) -> Unit 안의 쉼표에 속지 않기 위해."""
    # 화살표의 '>' 를 제네릭 닫는 괄호로 세지 않도록 먼저 치워 둔다.
    params = params.replace("->", "\x00\x00")
    parts, depth, buf = [], 0, []
    for ch in params:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    if "".join(buf).strip():
        parts.append("".join(buf))
    return [p.strip().replace("\x00\x00", "->") for p in parts if p.strip()]


def param_list(text, open_idx):
    """여는 괄호 위치에서 짝이 맞는 닫는 괄호까지 잘라 온다."""
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[open_idx + 1:i]
    return None


def check_trailing_lambda(path, text):
    problems = []
    for m in re.finditer(r"fun\s+([A-Z]\w*)\s*\(", text):
        name = m.group(1)
        params = param_list(text, m.end() - 1)
        if params is None:
            continue
        parts = split_params(params)
        if not parts:
            continue
        last = parts[-1]
        if any("-> Unit" in p for p in parts) and "Modifier" in last and "->" not in last:
            problems.append(
                f"{path}: {name}() 의 마지막 파라미터가 '{last}' 입니다. "
                "후행 람다가 콜백 대신 여기 붙습니다. modifier 를 앞으로 옮기세요."
            )
    return problems


def check_comment_nesting(path, text):
    """
    코틀린은 블록 주석이 중첩된다. 그래서 주석 안에 "/*" 가 들어가면 안쪽 주석이
    새로 열리고, 뒤따르는 "*/" 는 그 안쪽만 닫는다. 바깥 주석은 파일 끝까지
    열린 채로 남아 그 아래 코드를 통째로 삼킨다.

    실제로 겪었다. 주석에 "filesDir/henny/*.json" 이라고 경로를 적었더니
    "/*" 로 읽혀 클래스의 나머지가 전부 주석이 됐고, 컴파일러는 엉뚱하게
    "Unresolved reference" 부터 뱉었다. 원인이 주석이라는 걸 알아채기 어렵다.

    XML 쪽의 "--" 금지와 같은 종류의 함정이라 여기서 함께 잡는다.
    """
    problems = []
    depth = 0
    i = 0
    line = 1
    opened_at = None
    while i < len(text) - 1:
        if text[i] == "\n":
            line += 1
        if text[i:i + 2] == "/*":
            if depth == 0:
                opened_at = line
            else:
                problems.append(
                    f"{path}:{line}: 주석 안에 '/*' 가 있습니다. 코틀린은 주석이 "
                    "중첩되므로 이 아래 코드가 전부 주석으로 먹힙니다. "
                    "경로나 글로브를 적을 때 '/*' 가 되지 않게 풀어 쓰세요."
                )
            depth += 1
            i += 2
            continue
        if text[i:i + 2] == "*/":
            depth -= 1
            i += 2
            continue
        i += 1
    if depth > 0:
        problems.append(f"{path}:{opened_at}: 블록 주석이 닫히지 않았습니다.")
    return problems


def check_xml_comments(path, text):
    """
    XML 주석 안에는 붙임표를 두 개 연달아 쓸 수 없다. AAPT2 가 리소스를 합칠 때
    오류를 낸다. CSS 변수 이름(--bg)을 주석에 적었다가 빌드가 깨진 적이 있다.
    """
    problems = []
    for m in re.finditer(r"<!--(.*?)-->", text, re.S):
        body = m.group(1)
        if "--" in body:
            line = text[: m.start()].count("\n") + 1
            problems.append(
                f"{path}:{line}: XML 주석 안에 '--' 가 있습니다. "
                "AAPT2 가 오류로 처리합니다. 다르게 풀어 쓰세요."
            )
    return problems


def main():
    problems = []
    files = sorted(glob.glob(SRC, recursive=True))
    for path in files:
        text = open(path, encoding="utf-8").read()
        problems += check_balance(path, text)
        problems += check_trailing_lambda(path, text)
        problems += check_comment_nesting(path, text)

    # 리소스와 매니페스트의 XML 주석도 함께 본다.
    xml_files = sorted(glob.glob("app/src/main/**/*.xml", recursive=True))
    for path in xml_files:
        problems += check_xml_comments(path, open(path, encoding="utf-8").read())
    files = files + xml_files

    if problems:
        print(f"문제 {len(problems)}건:")
        for p in problems:
            print(f"  - {p}")
        return 1
    print(f"{len(files)}개 파일 확인, 문제 없음")
    return 0


if __name__ == "__main__":
    sys.exit(main())
