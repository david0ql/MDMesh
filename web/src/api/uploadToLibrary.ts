import {
  addApplicationVersion, commitUpload, getVersions, isNewVersion, listApplications, saveAndroidApplication, uniqueApkName, uploadApk,
  type Application, type ApplicationVersion,
} from './applications';

export type UploadOutcome =
  | { kind: 'new' | 'version'; app: Application; note: string }
  | { kind: 'error'; note: string };

/**
 * Upload an APK and put it in the Library the way the Apps page does: a new app becomes a Library entry; an app
 * already there takes a higher versionCode, or the same one under a new version name, as its new version (every policy
 * using it moves to it); an older one, or one already there, is refused. Returns the up-to-date Library app.
 */
export async function uploadApkToLibrary(file: File): Promise<UploadOutcome> {
  const up = await uploadApk(file);
  const fd = up.fileDetails;
  if (!fd?.pkg) return { kind: 'error', note: 'No se pudo leer el paquete del APK.' };
  let url: string | undefined;
  try { url = (await commitUpload(up.serverPath, uniqueApkName(fd.pkg, fd.version, file.name))).url || undefined; } catch { url = undefined; }
  const find = async () => (await listApplications(fd.pkg).catch(() => [] as Application[])).find((a) => a.pkg === fd.pkg);
  const existing = await find();
  if (existing?.id) {
    const versions = await getVersions(existing.id).catch(() => [] as ApplicationVersion[]);
    const current = versions.reduce((m, v) => Math.max(m, v.versionCode ?? 0), 0);
    const vc = fd.versionCode ?? 0;
    if (!isNewVersion(versions, vc, fd.version)) {
      return { kind: 'error', note: vc === current
        ? `${fd.pkg} ${fd.version ?? ''} (versionCode ${vc}) ya está en la Biblioteca.`
        : `El versionCode ${vc} es menor que ${current}: los teléfonos nunca bajan de versión.` };
    }
    if (!url) return { kind: 'error', note: 'No se pudo alojar el archivo en el servidor.' };
    await addApplicationVersion({ applicationId: existing.id, version: fd.version, versionCode: vc, url });
    const fresh = (await find()) ?? existing;
    return { kind: 'version', app: fresh, note: `${fd.name || fd.pkg} ${fd.version ?? ''}: nueva versión (versionCode ${vc}).` };
  }
  if (!url) return { kind: 'error', note: 'No se pudo alojar el archivo en el servidor.' };
  const saved = await saveAndroidApplication({
    name: fd.name || fd.pkg, pkg: fd.pkg, url, version: fd.version, versionCode: fd.versionCode, type: 'app',
  });
  const fresh = (await find()) ?? saved;
  return { kind: 'new', app: fresh, note: `${fd.name || fd.pkg} ${fd.version ?? ''} agregada a la Biblioteca.` };
}
