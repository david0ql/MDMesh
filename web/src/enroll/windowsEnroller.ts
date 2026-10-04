import ps1 from './inscribir-windows.ps1?raw';

/**
 * The Windows enroller a non-technical person double-clicks: one .cmd that runs the PowerShell script embedded below
 * it (no execution-policy prompt, nothing to install), with this server and a folder's enrollment code already in.
 * Kept as CRLF + no BOM: cmd.exe needs both. The PowerShell part is read back as UTF-8 by the launcher line.
 */
export function buildWindowsEnroller(server: string, code: string, folder: string): string {
  const q = (v: string) => v.replace(/'/g, "''");
  const script = ps1
    .replace("'__SERVER__'", `'${q(server)}'`)
    .replace("'__CODE__'", `'${q(code)}'`)
    .replace("'__FOLDER__'", `'${q(folder)}'`);
  const launcher = [
    '@echo off',
    'chcp 65001 >nul',
    'title DallyControl - Inscribir telefono',
    'set "DC_SELF=%~f0"',
    'powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$t=[IO.File]::ReadAllText($env:DC_SELF,[Text.Encoding]::UTF8); $i=$t.IndexOf(\'#\'+\'#BEGIN-PS\'); Invoke-Expression $t.Substring($i)"',
    'echo.',
    'pause',
    'exit /b',
    '##BEGIN-PS',
  ].join('\r\n');
  return `${launcher}\r\n${script.replace(/\r?\n/g, '\r\n')}`;
}

/** A file name Windows accepts, with the folder in it. */
export const enrollerFileName = (folder: string) =>
  `Inscribir-DallyControl-${folder.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^A-Za-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'carpeta'}.cmd`;
