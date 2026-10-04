# DallyControl: inscribir un teléfono SIN formatearlo, desde Windows.
# Lo genera la consola (Inscribir -> Sin formatear -> "Descargar inscriptor para Windows") con el servidor y el código
# de la carpeta ya puestos. Compatible con Windows PowerShell 5.1 (Windows 10/11). No instala nada en el sistema: las
# herramientas de Android (adb) se descargan a %LOCALAPPDATA%\DallyControl.
$Server = '__SERVER__'
$Code = '__CODE__'
$Folder = '__FOLDER__'
$AgentPkg = 'com.dallycontrol.agent'
if ($env:DC_AGENT_PKG) { $AgentPkg = $env:DC_AGENT_PKG }   # pruebas con la versión de desarrollo
$VncPkg = 'net.christianbeier.droidvnc_ng'

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }
if ($env:LOCALAPPDATA) { $Work = Join-Path $env:LOCALAPPDATA 'DallyControl' } else { $Work = Join-Path ([IO.Path]::GetTempPath()) 'DallyControl' }
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$Log = Join-Path $Work ('inscripcion-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
try { Start-Transcript -Path $Log -Append | Out-Null } catch { }

function Titulo($t) { Write-Host ''; Write-Host ('=== ' + $t + ' ===') -ForegroundColor Cyan }
function Paso($t) { Write-Host ('  -> ' + $t) }
function Ok($t) { Write-Host ('  OK  ' + $t) -ForegroundColor Green }
function Aviso($t) { Write-Host ('  !   ' + $t) -ForegroundColor Yellow }
function Falla($t) {
  Write-Host ''
  Write-Host ('  X   ' + $t) -ForegroundColor Red
  Write-Host ''
  Write-Host ('  Si necesitas ayuda, envía a soporte este archivo: ' + $Log)
  try { Stop-Transcript | Out-Null } catch { }
  exit 1
}
function Pregunta($t) { Write-Host ''; return (Read-Host ('  ' + $t)) }
function Espera($t) { Write-Host ''; [void](Read-Host ('  ' + $t + ' y luego presiona Enter')) }

# --- adb -------------------------------------------------------------------------------------------------------------
function Get-Adb {
  if ($env:DC_ADB) { return $env:DC_ADB }
  $adb = Join-Path $Work 'platform-tools\adb.exe'
  if (Test-Path $adb) { return $adb }
  Paso 'Descargando las herramientas de Android de Google (una sola vez, unos 7 MB)...'
  $zip = Join-Path $Work 'platform-tools.zip'
  try {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive -Force -Path $zip -DestinationPath $Work
    Remove-Item -Force $zip
  } catch { Falla ('No se pudieron descargar las herramientas de Android. ¿Hay Internet en este computador? (' + $_.Exception.Message + ')') }
  if (-not (Test-Path $adb)) { Falla 'Las herramientas de Android no quedaron listas.' }
  return $adb
}

$script:Serial = $null
function Adb {
  $a = @()
  if ($script:Serial) { $a += @('-s', $script:Serial) }
  $a += $args
  $out = & $script:AdbExe @a 2>&1 | Out-String
  return $out.Replace("`r", '').Trim()
}

function Get-Devices {
  $lines = (& $script:AdbExe devices 2>&1 | Out-String).Replace("`r", '').Split("`n")
  $list = @()
  foreach ($l in $lines) {
    if ($l -match '^(\S+)\s+(device|unauthorized|offline)\s*$') { $list += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] } }
  }
  return $list
}

# Waits until exactly one phone is connected and authorized (USB: the phone asks "¿Permitir depuración USB?").
function Wait-Phone([string]$want) {
  $warned = @{}
  for ($i = 0; $i -lt 150; $i++) {
    $devs = @(Get-Devices)
    if ($want) { $devs = @($devs | Where-Object { $_.Serial -eq $want }) }
    $ready = @($devs | Where-Object { $_.State -eq 'device' })
    if ($ready.Count -eq 1) { return $ready[0].Serial }
    if ($ready.Count -gt 1) {
      Aviso 'Hay más de un teléfono conectado. Deja conectado solo el que vas a inscribir.'
      Espera 'Desconecta los demás'
      continue
    }
    if (@($devs | Where-Object { $_.State -eq 'unauthorized' }).Count -gt 0 -and -not $warned['u']) {
      Aviso 'En la pantalla del teléfono apareció "¿Permitir depuración USB?": marca "Permitir siempre desde este computador" y toca PERMITIR.'
      $warned['u'] = $true
    } elseif (@($devs).Count -eq 0 -and -not $warned['n'] -and $i -gt 2) {
      Aviso 'Todavía no veo el teléfono. Revisa: cable conectado (que sea de datos, no solo de carga) y "Depuración USB" activada.'
      $warned['n'] = $true
    } elseif (@($devs).Count -eq 0 -and -not $warned['d'] -and $i -gt 25 -and -not $want) {
      Aviso 'Si sigue sin aparecer, puede que a este computador le falte el controlador USB de la marca del teléfono.'
      Aviso 'Más fácil: cierra esta ventana, vuelve a abrir el programa y elige 2 (Wi-Fi): no necesita controladores.'
      $warned['d'] = $true
    }
    Start-Sleep -Seconds 2
  }
  return $null
}

# --- inicio ----------------------------------------------------------------------------------------------------------
Clear-Host
Write-Host ''
Write-Host '  DallyControl - Inscribir un teléfono sin formatear' -ForegroundColor Cyan
Write-Host '  ------------------------------------------------'
Write-Host ('  Servidor: ' + $Server)
if ($Folder) { Write-Host ('  Carpeta:  ' + $Folder) }
Write-Host ''
Write-Host '  El teléfono conserva sus fotos, archivos y WhatsApp. Ten a la mano la guía (PDF).'

Titulo 'Paso 1 de 5: preparar este computador'
$script:AdbExe = Get-Adb
& $script:AdbExe start-server 2>&1 | Out-Null
Ok 'Herramientas de Android listas.'

Titulo 'Paso 2 de 5: conectar el teléfono'
Write-Host '  ¿Cómo lo vas a conectar?'
Write-Host '    1 = Con cable USB'
Write-Host '    2 = Sin cable, por Wi-Fi (Android 11 o superior; el teléfono y el computador en la misma red Wi-Fi)'
$modo = ''
while ($modo -ne '1' -and $modo -ne '2') { $modo = (Pregunta 'Escribe 1 o 2 y presiona Enter').Trim() }

if ($modo -eq '1') {
  Write-Host ''
  Write-Host '  En el teléfono: Opciones para desarrolladores -> activa "Depuración USB". Luego conecta el cable.'
  $s = Wait-Phone ''
  if (-not $s) { Falla 'No se detectó el teléfono por cable. Revisa el cable y la "Depuración USB", o vuelve a abrir este programa y elige 2 (Wi-Fi), que no necesita controladores.' }
  $script:Serial = $s
} else {
  Write-Host ''
  Write-Host '  En el teléfono: Opciones para desarrolladores -> toca "Depuración inalámbrica" (el nombre) -> actívala ->'
  Write-Host '  "Permitir" -> "Vincular dispositivo con código de sincronización" (o "...con código de vinculación").'
  Write-Host '  Aparecen un "Código de vinculación de Wi-Fi" de 6 números y una "Dirección IP y puerto".'
  $paired = $false
  for ($t = 0; $t -lt 4 -and -not $paired; $t++) {
    $pin = (Pregunta 'Escribe el código de 6 números (Código de vinculación de Wi-Fi)').Trim()
    $addr = (Pregunta 'Escribe la "Dirección IP y puerto" de ese mismo cuadro (ej. 192.168.1.5:41797)').Trim()
    if ($addr -notmatch '^[0-9.]+:[0-9]+$' -or $pin -notmatch '^[0-9]{6}$') { Aviso 'La dirección o el código no tienen el formato esperado. Intenta de nuevo.'; continue }
    $r = & $script:AdbExe pair $addr $pin 2>&1 | Out-String
    if ($r -match 'Successfully paired') { $paired = $true; Ok 'Vinculado.' }
    else { Aviso ('No se pudo vincular: ' + $r.Trim() + '. El código cambia cada vez: vuelve a tocar "Vincular dispositivo con código de sincronización".') }
  }
  if (-not $paired) { Falla 'No se pudo vincular por Wi-Fi. Revisa que el teléfono y el computador estén en la misma red Wi-Fi.' }
  $ip = $addr.Split(':')[0]
  # Después de vincular, el teléfono se anuncia en la red: buscarlo; si no aparece, pedir la dirección de conexión.
  $s = $null
  for ($i = 0; $i -lt 6 -and -not $s; $i++) {
    Start-Sleep -Seconds 2
    $s = (@(Get-Devices) | Where-Object { $_.State -eq 'device' -and $_.Serial -like ($ip + ':*') } | Select-Object -First 1).Serial
    if (-not $s) {
      $m = (& $script:AdbExe mdns services 2>&1 | Out-String)
      if ($m -match ('(' + [regex]::Escape($ip) + ':[0-9]+)')) { & $script:AdbExe connect $Matches[1] 2>&1 | Out-Null }
    }
  }
  while (-not $s) {
    Write-Host ''
    Write-Host '  Cierra el cuadro del código. En la pantalla "Depuración inalámbrica", debajo de "Nombre del dispositivo",'
    Write-Host '  aparece otra "Dirección IP y puerto" (el puerto es distinto al del código).'
    $conn = (Pregunta 'Escribe esa dirección (ej. 192.168.1.5:37215)').Trim()
    $r = & $script:AdbExe connect $conn 2>&1 | Out-String
    if ($r -match 'connected to') { $s = Wait-Phone $conn } else { Aviso ('No se pudo conectar: ' + $r.Trim()) }
  }
  $script:Serial = $s
}
$model = Adb shell getprop ro.product.model
$brand = Adb shell getprop ro.product.manufacturer
$release = Adb shell getprop ro.build.version.release
$sdk = [int](Adb shell getprop ro.build.version.sdk)
Ok ('Conectado: ' + $brand + ' ' + $model + ' (Android ' + $release + ')')

Titulo 'Paso 3 de 5: revisar las cuentas del teléfono'
# Android solo deja que DallyControl administre un teléfono SIN cuentas mientras se inscribe. Quitar una cuenta no borra
# nada del teléfono; al final se vuelve a agregar.
$already = (Adb shell dumpsys device_policy) -match ([regex]::Escape($AgentPkg) + '/')
if ($already) {
  Ok 'DallyControl ya administra este teléfono.'
} else {
  while ($true) {
    $acc = @()
    foreach ($l in (Adb shell dumpsys account).Split("`n")) {
      if ($l -match '^\s*Account \{name=(.+), type=(.+)\}\s*$') {
        $acc += ('      - ' + $Matches[1] + '   (' + $Matches[2] + ')')
      }
    }
    $acc = @($acc | Select-Object -Unique)
    if ($acc.Count -eq 0) { Ok 'El teléfono no tiene cuentas: se puede inscribir.'; break }
    Aviso 'El teléfono tiene estas cuentas, y Android no deja inscribirlo mientras estén:'
    $acc | ForEach-Object { Write-Host $_ }
    Write-Host '  Quítalas en Ajustes -> "Cuentas" (o "Contraseñas y cuentas") -> toca cada una -> "Quitar cuenta".'
    Write-Host '  No se borra nada del teléfono: las vuelves a agregar al terminar.'
    Espera 'Quita las cuentas'
  }
}

Titulo 'Paso 4 de 5: instalar DallyControl'
function Get-Apk($name) {
  $f = Join-Path $Work $name
  try { Invoke-WebRequest -UseBasicParsing -Uri ($Server + '/files/' + $name) -OutFile $f } catch { Falla ('No se pudo descargar ' + $name + ' del servidor (' + $_.Exception.Message + ')') }
  return $f
}
if ($env:DC_AGENT_APK) { $agentApk = $env:DC_AGENT_APK } else { Paso 'Descargando DallyControl...'; $agentApk = Get-Apk 'dallycontrol-agent.apk' }
Paso 'Instalando DallyControl en el teléfono...'
$r = Adb install -r -g $agentApk
if ($r -notmatch 'Success') { Falla ('No se pudo instalar DallyControl: ' + $r) }
Ok 'DallyControl instalado.'
if ($sdk -ge 24) {
  Paso 'Instalando el soporte remoto (para ver y manejar el teléfono desde la consola)...'
  $vnc = Get-Apk 'droidvnc-ng.apk'
  $r = Adb install -r -g $vnc
  if ($r -match 'Success') { Ok 'Soporte remoto instalado.' } else { Aviso 'No se pudo instalar el soporte remoto; el resto sigue igual.' }
}

if (-not $already) {
  Paso 'Haciendo que DallyControl administre el teléfono...'
  $ok = $false
  for ($t = 0; $t -lt 3 -and -not $ok; $t++) {
    $r = Adb shell dpm set-device-owner ($AgentPkg + '/com.dallycontrol.agent.admin.AdminReceiver')
    if ($r -match 'Success') { $ok = $true } else { Start-Sleep -Seconds 4 }
  }
  if (-not $ok) {
    if ($r -match 'account') { Falla 'Android dice que el teléfono todavía tiene cuentas. Quítalas todas (también las de Samsung, Xiaomi, WhatsApp Business, etc.) y vuelve a abrir este programa.' }
    if ($r -match 'several users|multiple users|user') { Falla 'El teléfono tiene otro usuario o un "perfil de trabajo". Quítalo en Ajustes -> Sistema -> Varios usuarios (o Cuentas -> Trabajo) y vuelve a abrir este programa.' }
    if ($r -match 'already') { Falla 'Otro programa ya administra este teléfono (otro MDM). Hay que quitarlo primero.' }
    Falla ('Android no aceptó a DallyControl como administrador: ' + $r)
  }
  Ok 'DallyControl administra el teléfono.'
}
# Permisos que solo se dan desde el computador (uso de apps, notificaciones para el diagnóstico, archivos, remoto).
Adb shell appops set $AgentPkg GET_USAGE_STATS allow | Out-Null
Adb shell cmd notification allow_listener ($AgentPkg + '/com.dallycontrol.agent.diag.DcNotificationListener') | Out-Null
if ($sdk -ge 30) { Adb shell appops set --uid $AgentPkg MANAGE_EXTERNAL_STORAGE allow | Out-Null }
if ((Adb shell pm path $VncPkg) -match 'package:') {
  Adb shell appops set $VncPkg PROJECT_MEDIA allow | Out-Null
  Adb shell pm grant $AgentPkg android.permission.WRITE_SECURE_SETTINGS | Out-Null
}

Titulo 'Paso 5 de 5: conectar con la consola'
$r = Adb shell am broadcast -a com.dallycontrol.agent.ADB_PROVISION -n ($AgentPkg + '/com.dallycontrol.agent.provisioning.AdbProvisionReceiver') --es server_url $Server --es enroll_token $Code
if ($r -match 'already enrolled') { Ok 'El teléfono ya estaba inscrito en la consola.' }
elseif ($r -match 'data="ok') { Ok 'Inscrito. En unos segundos aparece en la consola.' }
else { Falla ('No se pudo inscribir: ' + $r) }
Adb shell am start -n ($AgentPkg + '/com.dallycontrol.agent.MainActivity') | Out-Null
# Control remoto: el agente activa solo el servicio de entrada del soporte remoto; si no alcanzó, se activa desde aquí.
if ((Adb shell pm path $VncPkg) -match 'package:') {
  $svc = $VncPkg + '/' + $VncPkg + '.InputService'
  $cur = ''
  for ($i = 0; $i -lt 10; $i++) { $cur = Adb shell settings get secure enabled_accessibility_services; if ($cur -match [regex]::Escape($svc)) { break }; Start-Sleep -Seconds 1 }
  if ($cur -notmatch [regex]::Escape($svc)) {
    if (-not $cur -or $cur -eq 'null') { $cur = $svc } else { $cur = $cur + ':' + $svc }
    Adb shell settings put secure enabled_accessibility_services $cur | Out-Null
    Adb shell settings put secure accessibility_enabled 1 | Out-Null
  }
  Ok 'Soporte remoto listo.'
}

Titulo 'Listo'
Write-Host '  1. Vuelve a agregar las cuentas que quitaste (Ajustes -> Cuentas -> Agregar cuenta).'
Write-Host '  2. La depuración ya no hace falta.'
$off = (Pregunta '¿La apago ahora en el teléfono? Escribe S y Enter (o solo Enter para dejarla)').Trim()
if ($off -match '^[sS]') {
  Adb shell settings put global adb_wifi_enabled 0 | Out-Null
  Adb shell settings put global development_settings_enabled 0 | Out-Null
  Adb shell settings put global adb_enabled 0 | Out-Null
  Ok 'Depuración apagada.'
}
Write-Host ''
Write-Host ('  Registro de esta inscripción: ' + $Log)
try { Stop-Transcript | Out-Null } catch { }
