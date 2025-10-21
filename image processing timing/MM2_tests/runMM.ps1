# runMM.ps1
# Compile + Run Micro-Manager Java tests

param(
    [string]$class = "MMHelloWorld",  # default class if none given
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$restArgs
)

# --- CONFIG ---
$mmInstall = "C:\Program Files\Micro-Manager-2.0"   # adjust if different
$srcDir = "src"
$bin = "bin"
$lib = "lib\mmcorej.jar"
$native = "native"

# --- Help option ---
if ($class -eq "-help" -or $class -eq "/?" -or $class -eq "--help") {
    Write-Host "Usage:"
    Write-Host "  .\runMM.ps1 [ClassName] [args...]"
    Write-Host ""
    Write-Host "Available classes (from src/):"
    Get-ChildItem -Path $srcDir -Filter *.java | ForEach-Object {
        $name = [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
        Write-Host "   $name"
    }
    Write-Host ""
    Write-Host "Examples:"
    Write-Host "  .\runMM.ps1 MMHelloWorld"
    Write-Host "  .\runMM.ps1 MMAcquireAndWriteBenchmark 1024 1024 10"
    exit 0
}

# --- Add Micro-Manager folder to PATH so device adapter DLLs can be found ---
$env:PATH = "$mmInstall;$env:PATH"

# --- Ensure bin folder exists ---
if (!(Test-Path $bin)) {
    New-Item -ItemType Directory -Force -Path $bin | Out-Null
}

# --- Compile all .java files in src ---
Write-Host "Compiling Java..."
$javaFiles = Get-ChildItem -Path $srcDir -Filter *.java -Recurse | ForEach-Object { $_.FullName }
javac -cp $lib -d $bin $javaFiles

if ($LASTEXITCODE -ne 0) {
    Write-Error "Compilation failed!"
    exit 1
}

# --- Run specified class with args ---
Write-Host "Running $class $restArgs"
java "-Djava.library.path=$native" -cp "$bin;$lib" $class @restArgs
