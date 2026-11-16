"""Run plain JVM Kotlin tests in scratch, without shared Gradle output."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
patterns = [
    'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20/**/*.jar',
    'org.jetbrains.kotlin/kotlin-stdlib/2.1.20/**/*.jar',
    'org.jetbrains.kotlin/kotlin-reflect/1.6.10/**/*.jar',
    'org.jetbrains.kotlin/kotlin-script-runtime/2.1.20/**/*.jar',
    'org.jetbrains.intellij.deps/trove4j/**/*.jar',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/**/*.jar',
    'org.jetbrains/annotations/**/*.jar',
    'junit/junit/4.13.2/**/*.jar',
    'org.hamcrest/hamcrest-core/1.3/**/*.jar',
]
cp = os.pathsep.join(str(f) for pattern in patterns for f in cache.glob(pattern))
out = Path(os.environ['LOCALAPPDATA']) / 'hermes/cache/scratch/taskai-plain-tests'
out.mkdir(parents=True, exist_ok=True)
java = root / '.tooling/java/bin/java.exe'
classes = sys.argv[1]
# Test classes are comma-separated. Remaining command-line arguments are Kotlin files.
args = ['-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(out)] + [str(root / arg) for arg in sys.argv[2:]]
argfile = out / 'compile.args'
argfile.write_text('\n'.join('"' + arg.replace('\\', '/') + '"' for arg in args), encoding='utf-8')
subprocess.run([str(java), '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(argfile)], check=True)
subprocess.run([str(java), '-cp', str(out) + os.pathsep + cp, 'org.junit.runner.JUnitCore'] + classes.split(','), check=True)
