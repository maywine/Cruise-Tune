#!/usr/bin/env python3
"""Use a standard Android/JDK installation or the task-local verified toolchain."""
from pathlib import Path
import json, os, subprocess, sys, threading, tempfile
root = Path(__file__).resolve().parent.parent
config = Path.home() / '.cache/cruise-tune-toolchain/paths.json'
env = os.environ.copy()
if config.exists():
    paths = json.loads(config.read_text())
    env['JAVA_HOME'] = paths['java']
    env['ANDROID_HOME'] = paths['sdk']
    env['PATH'] = paths['java'] + '/bin:' + env.get('PATH', '')
    launcher = paths['gradle']
else:
    launcher = str(root / 'android-app/gradlew')
args = sys.argv[1:] or [':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug']
server = None
temporary = None
if env.get('CRUISE_CURL_MAVEN') == '1':
    from curl_maven_proxy import create_server
    server = create_server(config.parent / 'google-maven-cache')
    threading.Thread(target=server.serve_forever, daemon=True).start()
    temporary = tempfile.TemporaryDirectory(prefix='cruise-gradle-')
    init = Path(temporary.name) / 'repositories.gradle'
    url = f'http://127.0.0.1:{server.server_address[1]}'
    init.write_text('''settingsEvaluated { settings ->
  settings.pluginManagement.repositories {
    clear()
    mavenCentral()
    maven {
      url = uri("URL"); allowInsecureProtocol = true
      content { includeGroupByRegex("com[.]android.*"); includeGroupByRegex("androidx[.].*"); includeGroupByRegex("com[.]google[.]android.*"); includeGroup("com.google.testing.platform") }
    }
  }
  settings.dependencyResolutionManagement.repositories {
    clear()
    mavenCentral()
    maven {
      url = uri("URL"); allowInsecureProtocol = true
      content { includeGroupByRegex("com[.]android.*"); includeGroupByRegex("androidx[.].*"); includeGroupByRegex("com[.]google[.]android.*"); includeGroup("com.google.testing.platform") }
    }
  }
}
'''.replace('URL', url))
    args = ['--init-script', str(init), *args]
try:
    code = subprocess.call([launcher, '--no-daemon', *args], cwd=root/'android-app', env=env)
finally:
    if server: server.shutdown()
    if temporary: temporary.cleanup()
raise SystemExit(code)
