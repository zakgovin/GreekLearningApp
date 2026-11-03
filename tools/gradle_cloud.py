#!/usr/bin/env python3
"""Cloud build entry point. Uses platform proxy and trusted system certificates."""
import os
from pathlib import Path
import subprocess
import sys
import urllib.parse

root = Path(__file__).resolve().parents[1]
tools = Path('/workspace/toolchains')
os.environ['JAVA_HOME'] = str(tools / 'jdk-21.0.13+9')
os.environ['ANDROID_HOME'] = str(tools / 'android')
os.environ['ANDROID_USER_HOME'] = str(tools / 'android-user')
proxy = urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY', os.environ.get('https_proxy', '')))
args = [str(tools / 'gradle-8.11.1/bin/gradle'), '--no-daemon', '-g', '/workspace/.gradle',
        '-Duser.home=/workspace/toolchains/java-user']
trust = Path('/etc/ssl/certs/java/cacerts')
if trust.is_file():
    args.append('-Djavax.net.ssl.trustStore=' + str(trust))
if proxy.hostname:
    for protocol in ('http', 'https'):
        args += [f'-D{protocol}.proxyHost={proxy.hostname}', f'-D{protocol}.proxyPort={proxy.port or 80}']
sys.exit(subprocess.call(args + (sys.argv[1:] or [':app:assembleDebug', ':app:testDebugUnitTest']), cwd=root))
