import {
  addApplicationVersion, commitUpload, getVersions, isNewVersion, listApplications, saveAndroidApplication, uniqueApkName, uploadApk,
  type Application, type ApplicationVersion,
} from './applications';
import { listAppVersions, setVersionLabel } from './versions';

export type UploadOutcome =
  /** versionId: the library version this file is (new, or the one already there with the same name and code). */
  | { kind: 'new' | 'version' | 'existing'; app: Application; note: string; versionId?: number; version?: string }
  | { kind: 'error'; note: string };

/** The library version of [appId] with this name and code (after an upload), named [label] when one is given. */
async function versionIdOf(appId: number, version: string | undefined, code: number, label?: string): Promise<number | undefined> {
  const list = await listAppVersions(appId).catch(() => []);
  const v = list.find((x) => (x.versionCode ?? 0) === code && (x.version ?? '').trim() === (version ?? '').trim());
  if (v && label && label.trim()) await setVersionLabel(v.id, label.trim()).catch(() => undefined);
  return v?.id;
}

/**
 * Upload an APK and put it in the Library the way the Apps page does: a new app becomes a Library entry; an app
 * already there takes a higher versionCode, or the same one under a new version name, as its new version (every policy
 * using it moves to it); a file that IS a version already there returns that version (so a policy can use it); an
 * older one that is not there is refused — unless it goes into a group of builds ([intoGroup]: each policy uses the
 * build it is given, so an older code is just another build; the value is the group's package). [label] names the version. Returns the up-to-date Library app.
 */
export async function uploadApkToLibrary(file: File, label?: string, intoGroup?: string): Promise<UploadOutcome> {
  const up = await uploadApk(file);
  const fd = up.fileDetails;
  if (!fd?.pkg) return { kind: 'error', note: 'No se pudo leer el paquete del APK.' };
  if (intoGroup && fd.pkg !== intoGroup) {
    return { kind: 'error', note: `Ese APK es de otra app (${fd.pkg}); este grupo es de ${intoGroup}.` };
  }
  let url: string | undefined;
  try { url = (await commitUpload(up.serverPath, uniqueApkName(fd.pkg, fd.version, file.name))).url || undefined; } catch { url = undefined; }
  const find = async () => (await listApplications(fd.pkg).catch(() => [] as Application[])).find((a) => a.pkg === fd.pkg);
  const existing = await find();
  if (existing?.id) {
    const versions = await getVersions(existing.id).catch(() => [] as ApplicationVersion[]);
    const current = versions.reduce((m, v) => Math.max(m, v.versionCode ?? 0), 0);
    const vc = fd.versionCode ?? 0;
    if (!isNewVersion(versions, vc, fd.version)) {
      // The very same version is already there: use it (a policy can pick it) instead of refusing the file.
      const same = versions.find((v) => (v.versionCode ?? 0) === vc && (v.version ?? '').trim() === (fd.version ?? '').trim());
      if (same) {
        const versionId = await versionIdOf(existing.id, fd.version, vc, label);
        return { kind: 'existing', app: existing, versionId: versionId ?? same.id, version: fd.version,
          note: `${fd.name || fd.pkg} ${fd.version ?? ''} (versionCode ${vc}) ya estaba en la Biblioteca${label ? `; ahora se llama «${label.trim()}»` : ''}.` };
      }
      if (!intoGroup) return { kind: 'error', note: `El versionCode ${vc} es menor que ${current} y esa versión no está en la Biblioteca.` };
    }
    if (!url) return { kind: 'error', note: 'No se pudo alojar el archivo en el servidor.' };
    await addApplicationVersion({ applicationId: existing.id, version: fd.version, versionCode: vc, url });
    const fresh = (await find()) ?? existing;
    const versionId = await versionIdOf(existing.id, fd.version, vc, label);
    return { kind: 'version', app: fresh, versionId, version: fd.version, note: `${fd.name || fd.pkg} ${fd.version ?? ''}: nueva versión (versionCode ${vc}).` };
  }
  if (!url) return { kind: 'error', note: 'No se pudo alojar el archivo en el servidor.' };
  const saved = await saveAndroidApplication({
    name: fd.name || fd.pkg, pkg: fd.pkg, url, version: fd.version, versionCode: fd.versionCode, type: 'app',
  });
  const fresh = (await find()) ?? saved;
  const versionId = fresh?.id ? await versionIdOf(fresh.id, fd.version, fd.versionCode ?? 0, label) : undefined;
  return { kind: 'new', app: fresh, versionId, version: fd.version, note: `${fd.name || fd.pkg} ${fd.version ?? ''} agregada a la Biblioteca.` };
}
