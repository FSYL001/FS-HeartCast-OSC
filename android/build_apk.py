import os
import sys
import shutil
import zipfile
import subprocess
import hashlib

def main():
    root = r"C:\Users\JKH\Desktop\GIT\0Amyhub\HOSC\android"
    tc = r"C:\Users\JKH\AppData\Local\Temp\dsh-NugnZR\android-tc"
    jbr = r"C:\Program Files\JetBrains\PyCharm Community Edition 2024.3.1.1\jbr"
    
    aapt = os.path.join(tc, "bt", "android-14", "aapt.exe")
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
        
    # 2. Package resources with aapt
    print("[2/7] 打包资源并生成 R.java (aapt package)...")
    res_dir = os.path.join(src, "res")
    base_apk = os.path.join(build, "base.apk")
    if os.path.exists(base_apk):
        os.remove(base_apk)
        
    aapt_cmd = [
        aapt, "package",
        "-f", "-m",
        "-M", build_manifest,
        "-S", res_dir,
        "-I", android_jar,
        "-F", base_apk,
        "-J", gen_dir,
        "--auto-add-overlay",
        "--min-sdk-version", "26",
        "--target-sdk-version", "34",
        "--version-code", "5",
        "--version-name", "1.2.1"
    ]
    ret = subprocess.run(aapt_cmd, env=env)
    if ret.returncode != 0:
        raise RuntimeError(f"aapt package 失败，退出码 {ret.returncode}")
        
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
        raise RuntimeError(f"apksigner verify 校验失败")
        
    with open(final_apk, "rb") as f:
        sha256 = hashlib.sha256(f.read()).hexdigest().upper()
    size_kb = round(os.path.getsize(final_apk) / 1024, 1)
    
    print("\n==========================================")
    print("APK 构建并签名成功！")
    print(f"  文件路径: {final_apk}")
    print(f"  文件大小: {size_kb} KB ({os.path.getsize(final_apk)} 字节)")
    print(f"  SHA256  : {sha256}")
    print("==========================================")

if __name__ == "__main__":
    main()
