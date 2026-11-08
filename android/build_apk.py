import os
import sys
import shutil
import zipfile
import subprocess
import hashlib
import ctypes
from ctypes import wintypes

class PROCESS_INFORMATION(ctypes.Structure):
    _fields_ = [
        ('hProcess', wintypes.HANDLE),
        ('hThread', wintypes.HANDLE),
        ('dwProcessId', wintypes.DWORD),
        ('dwThreadId', wintypes.DWORD),
    ]

class STARTUPINFO(ctypes.Structure):
    _fields_ = [
        ('cb', wintypes.DWORD),
        ('lpReserved', wintypes.LPWSTR),
        ('lpDesktop', wintypes.LPWSTR),
        ('lpTitle', wintypes.LPWSTR),
        ('dwX', wintypes.DWORD),
        ('dwY', wintypes.DWORD),
        ('dwXSize', wintypes.DWORD),
        ('dwYSize', wintypes.DWORD),
        ('dwXCountChars', wintypes.DWORD),
        ('dwYCountChars', wintypes.DWORD),
        ('dwFillAttribute', wintypes.DWORD),
        ('dwFlags', wintypes.DWORD),
        ('wShowWindow', wintypes.WORD),
        ('cbReserved2', wintypes.WORD),
        ('lpReserved2', ctypes.c_void_p),
        ('hStdInput', wintypes.HANDLE),
        ('hStdOutput', wintypes.HANDLE),
        ('hStdError', wintypes.HANDLE),
    ]

class SECURITY_ATTRIBUTES(ctypes.Structure):
    _fields_ = [
        ('nLength', wintypes.DWORD),
        ('lpSecurityDescriptor', ctypes.c_void_p),
        ('bInheritHandle', wintypes.BOOL),
    ]

kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)

def run_win_cmd(cmd, timeout_sec=90):
    """通过带有 winsta0\\default 桌面站属性的 CreateProcess 运行命令，解决 Windows 下 64 位 AAPT2 报 0xC0000142 问题。"""
    sa = SECURITY_ATTRIBUTES()
    sa.nLength = ctypes.sizeof(sa)
    sa.bInheritHandle = True
    hRead, hWrite = wintypes.HANDLE(), wintypes.HANDLE()
    kernel32.CreatePipe(ctypes.byref(hRead), ctypes.byref(hWrite), ctypes.byref(sa), 0)
    kernel32.SetHandleInformation(hRead, 1, 0)

    pi = PROCESS_INFORMATION()
    si = STARTUPINFO()
    si.cb = ctypes.sizeof(si)
    si.dwFlags = 0x00000100  # STARTF_USESTDHANDLES
    si.hStdOutput = hWrite
    si.hStdError = hWrite
    si.lpDesktop = r'winsta0\default'

    ok = kernel32.CreateProcessW(None, cmd, None, None, True, 0, None, None, ctypes.byref(si), ctypes.byref(pi))
    kernel32.CloseHandle(hWrite)
    if not ok:
        kernel32.CloseHandle(hRead)
        raise RuntimeError(f"CreateProcess 失败: {ctypes.get_last_error()}，命令: {cmd}")

    kernel32.WaitForSingleObject(pi.hProcess, timeout_sec * 1000)
    code = wintypes.DWORD()
    kernel32.GetExitCodeProcess(pi.hProcess, ctypes.byref(code))

    out = b''
    buf = ctypes.create_string_buffer(4096)
    read = wintypes.DWORD()
    while kernel32.ReadFile(hRead, buf, 4096, ctypes.byref(read), None) and read.value > 0:
        out += buf.raw[:read.value]
    kernel32.CloseHandle(hRead)
    kernel32.CloseHandle(pi.hProcess)
    kernel32.CloseHandle(pi.hThread)

    out_str = out.decode(errors='replace')
    if code.value != 0:
        raise RuntimeError(f"命令执行失败 (退出码 {hex(code.value)}):\n命令: {cmd}\n输出: {out_str}")
    return out_str

def main():
    root = r"C:\Users\JKH\Desktop\GIT\0Amyhub\HOSC\android"
    tc = r"C:\Users\JKH\AppData\Local\Temp\dsh-NugnZR\android-tc"
    jbr = r"C:\Program Files\JetBrains\PyCharm Community Edition 2024.3.1.1\jbr"

    aapt2 = os.path.join(root, "build", "aapt2-8.5.2", "aapt2.exe")
    if not os.path.exists(aapt2):
        aapt2 = os.path.join(tc, "bt", "android-14", "aapt2.exe")

    zipalign = os.path.join(tc, "bt", "android-14", "zipalign.exe")
    apksigner = os.path.join(tc, "bt", "android-14", "apksigner.bat")
    android_jar = os.path.join(tc, "plat", "android-34", "android.jar")
    d8_jar = os.path.join(root, "tools", "d8.jar")

    java = os.path.join(jbr, "bin", "java.exe")
    javac = os.path.join(jbr, "bin", "javac.exe")

    src = os.path.join(root, "app", "src", "main")
    build = os.path.join(root, "build")
    out_dir = os.path.join(root, "dist")

    os.makedirs(build, exist_ok=True)
    os.makedirs(out_dir, exist_ok=True)

    gen_dir = os.path.join(build, "gen")
    classes_dir = os.path.join(build, "classes")
    dex_dir = os.path.join(build, "dex")

    for d in [gen_dir, classes_dir, dex_dir]:
        if os.path.exists(d):
            shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d, exist_ok=True)

    env = os.environ.copy()
    env["JAVA_HOME"] = jbr
    env["PATH"] = os.path.join(jbr, "bin") + ";" + os.path.join(tc, "bt", "android-14") + ";" + env["PATH"]

    # 1. Manifest
    print("[1/7] 准备 AndroidManifest.xml...")
    src_manifest = os.path.join(src, "AndroidManifest.xml")
    with open(src_manifest, "r", encoding="utf-8") as f:
        manifest_text = f.read()
    if 'package=' not in manifest_text:
        manifest_text = manifest_text.replace('<manifest', '<manifest package="com.fsheartcast.osc"', 1)
    build_manifest = os.path.join(build, "AndroidManifest.xml")
    with open(build_manifest, "w", encoding="utf-8") as f:
        f.write(manifest_text)

    # 2. AAPT2 compile and link
    print("[2/7] 编译资源并生成 R.java (aapt2 compile & link)...")
    res_dir = os.path.join(src, "res")
    res_zip = os.path.join(build, "res.zip")
    base_apk = os.path.join(build, "base.apk")

    if os.path.exists(res_zip):
        os.remove(res_zip)
    if os.path.exists(base_apk):
        os.remove(base_apk)

    # aapt2 compile
    cmd_compile = f'"{aapt2}" compile --dir "{res_dir}" -o "{res_zip}"'
    run_win_cmd(cmd_compile)
    print(f"  res.zip 编译完成 ({os.path.getsize(res_zip)} 字节)")

    # aapt2 link
    cmd_link = (
        f'"{aapt2}" link -o "{base_apk}" '
        f'-I "{android_jar}" '
        f'--manifest "{build_manifest}" '
        f'-R "{res_zip}" '
        f'--java "{gen_dir}" '
        f'--auto-add-overlay '
        f'--min-sdk-version 26 '
        f'--target-sdk-version 34 '
        f'--version-code 5 '
        f'--version-name 1.2.1'
    )
    run_win_cmd(cmd_link)

    r_java = os.path.join(gen_dir, "com", "fsheartcast", "osc", "R.java")
    if not os.path.exists(base_apk) or not os.path.exists(r_java):
        raise RuntimeError(f"资源生成失败：base_apk={os.path.exists(base_apk)}, R.java={os.path.exists(r_java)}")
    print(f"  base.apk ({os.path.getsize(base_apk)} 字节) 和 R.java 生成成功！")

    # 3. Compile Java
    print("[3/7] 编译 Java 源码 (javac)...")
    java_sources = []
    for root_dir, _, files in os.walk(os.path.join(src, "java")):
        for file in files:
            if file.endswith(".java"):
                java_sources.append(os.path.join(root_dir, file))
    for root_dir, _, files in os.walk(gen_dir):
        for file in files:
            if file.endswith(".java"):
                java_sources.append(os.path.join(root_dir, file))

    print(f"  共 {len(java_sources)} 个 Java 源码文件")
    javac_cmd = [
        javac,
        "-encoding", "UTF-8",
        "-source", "11", "-target", "11",
        "-classpath", android_jar,
        "-d", classes_dir
    ] + java_sources
    ret = subprocess.run(javac_cmd, env=env)
    if ret.returncode != 0:
        raise RuntimeError(f"javac 失败，退出码 {ret.returncode}")
    print("  Java 编译成功！")

    # 4. Package to classes.jar and run d8
    print("[4/7] 打包 classes.jar 并生成 classes.dex (d8)...")
    classes_jar = os.path.join(build, "classes.jar")
    if os.path.exists(classes_jar):
        os.remove(classes_jar)
    with zipfile.ZipFile(classes_jar, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for root_dir, _, files in os.walk(classes_dir):
            for file in files:
                full_path = os.path.join(root_dir, file)
                arcname = os.path.relpath(full_path, classes_dir)
                zf.write(full_path, arcname)
    print(f"  classes.jar 生成成功 ({os.path.getsize(classes_jar)} 字节)")

    d8_cmd = [
        java, "-cp", d8_jar, "com.android.tools.r8.D8",
        "--lib", android_jar,
        "--min-api", "26",
        "--output", dex_dir,
        classes_jar
    ]
    ret = subprocess.run(d8_cmd, env=env)
    if ret.returncode != 0:
        raise RuntimeError(f"d8 失败，退出码 {ret.returncode}")

    dex_file = os.path.join(dex_dir, "classes.dex")
    if not os.path.exists(dex_file):
        raise RuntimeError("未找到 classes.dex！")
    print(f"  classes.dex 生成成功 ({os.path.getsize(dex_file)} 字节)")

    # 5. Insert classes.dex into base.apk
    print("[5/7] 将 classes.dex 写入 base.apk...")
    with zipfile.ZipFile(base_apk, "a", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(dex_file, "classes.dex")
    print(f"  base.apk 已包含 classes.dex ({os.path.getsize(base_apk)} 字节)")

    # 6. Zipalign
    print("[6/7] 执行 4 字节对齐 (zipalign)...")
    aligned_apk = os.path.join(build, "aligned.apk")
    if os.path.exists(aligned_apk):
        os.remove(aligned_apk)
    ret = subprocess.run([zipalign, "-f", "-p", "4", base_apk, aligned_apk], env=env)
    if ret.returncode != 0:
        raise RuntimeError(f"zipalign 失败，退出码 {ret.returncode}")
    print(f"  aligned.apk 对齐成功 ({os.path.getsize(aligned_apk)} 字节)")

    # 7. Apksigner
    print("[7/7] 签名 APK (apksigner)...")
    final_apk = os.path.join(out_dir, "FS-HeartCast-OSC-1.2.1-debug.apk")
    keystore = os.path.join(root, "debug.keystore")
    sign_cmd = [
        apksigner, "sign",
        "--ks", keystore,
        "--ks-pass", "pass:android",
        "--key-pass", "pass:android",
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--out", final_apk,
        aligned_apk
    ]
    ret = subprocess.run(sign_cmd, env=env)
    if ret.returncode != 0:
        raise RuntimeError(f"apksigner sign 失败，退出码 {ret.returncode}")

    verify_cmd = [apksigner, "verify", "--verbose", final_apk]
    ret = subprocess.run(verify_cmd, env=env)
    if ret.returncode != 0:
        raise RuntimeError("apksigner verify 校验失败")

    with open(final_apk, "rb") as f:
        sha256 = hashlib.sha256(f.read()).hexdigest().upper()
    size_kb = round(os.path.getsize(final_apk) / 1024, 1)

    # 复制到桌面及 Artifact 目录
    desktop_apk = r"C:\Users\JKH\Desktop\FS-HeartCast-OSC-1.2.1-debug.apk"
    shutil.copy2(final_apk, desktop_apk)

    artifact_apk = r"C:\Users\JKH\.gemini\antigravity\brain\d64f1fc9-3676-4be5-a643-d1075453d82d\FS-HeartCast-OSC-1.2.1-debug.apk"
    try:
        shutil.copy2(final_apk, artifact_apk)
    except Exception as e:
        print(f"  复制到 artifact 目录提示: {e}")

    print("\n==========================================")
    print("APK 构建并签名成功！")
    print(f"  产物路径 (仓库) : {final_apk}")
    print(f"  桌面交付路径     : {desktop_apk}")
    print(f"  文件大小         : {size_kb} KB ({os.path.getsize(final_apk)} 字节)")
    print(f"  SHA256           : {sha256}")
    print("==========================================")

if __name__ == "__main__":
    main()
