import { apiClient } from './client';

// Applications library — see com.hmdm.rest.resource.ApplicationResource (@Path /private/applications).
// All responses use the {status,data} envelope (apiClient unwraps to data).

export interface Application {
  id: number;
  name: string;
  pkg: string;
  version?: string;
  versionCode?: number;
  url?: string;
  apkHash?: string;
  icon?: string;
  iconId?: number;
  /** "app" | "web" */
  type?: string;
  system?: boolean;
  latestVersion?: number;
  runAfterInstall?: boolean;
  /** Split-bundle parts as a raw JSON string `[{url,sha256,name}]` (server stores it as text);
   *  present only for multi-part bundles. Use JSON.parse to read, JSON.stringify to send. */
  parts?: string;
}

export interface ApplicationVersion {
  id: number;
  applicationId: number;
  version?: string;
  versionCode?: number;
  url?: string;
  apkHash?: string;
  arch?: string | null;
  /** Split-bundle parts as a raw JSON string `[{url,sha256,name}]`; present only for bundle versions. */
  parts?: string;
}

// ApplicationConfigurationLink — the app↔configuration matrix.
// action: 0 = hide, 1 = install, 2 = remove.
export interface AppConfigLink {
  id?: number;
  configurationId: number;
  configurationName?: string;
  applicationId: number;
  applicationName?: string;
  action: number;
  showIcon?: boolean;
  remove?: boolean;
  notify?: boolean;
}

export interface LinkConfigurationsToAppRequest {
  applicationId: number;
  configurations: AppConfigLink[];
}

// Library apps fall into three buckets. "uploaded" = the APKs/apps an admin added
// (non-system); "system" = the large pre-seeded OS app list; "web" = web-app shortcuts.
export type AppCategory = 'uploaded' | 'system' | 'web';

export function appCategory(a: Application): AppCategory {
  if ((a.type ?? 'app') === 'web') return 'web';
  if (a.system) return 'system';
  return 'uploaded';
}

/** Create (id absent) or update an Android application in the Library. */
export async function saveAndroidApplication(app: Partial<Application>): Promise<Application> {
  return apiClient.put<Application>('/private/applications/android', app);
}

export async function listApplications(value?: string): Promise<Application[]> {
  const v = value?.trim();
  const path = v
    ? `/private/applications/search/${encodeURIComponent(v)}`
    : '/private/applications/search';
  return apiClient.get<Application[]>(path);
}

/**
 * Add a new version of an existing app. When it is the app's latest, the server moves every configuration using the
 * app to it and queues the install on their devices.
 */
export async function addApplicationVersion(v: {
  applicationId: number; version?: string; versionCode?: number; url: string;
  /** Split-bundle parts as a JSON string (see [Application.parts]). */
  parts?: string;
}): Promise<ApplicationVersion> {
  return apiClient.put<ApplicationVersion>('/private/applications/versions', v);
}

export async function getVersions(appId: number): Promise<ApplicationVersion[]> {
  return apiClient.get<ApplicationVersion[]>(`/private/applications/${appId}/versions`);
}

export async function getAppConfigLinks(appId: number): Promise<AppConfigLink[]> {
  return apiClient.get<AppConfigLink[]>(`/private/applications/configurations/${appId}`);
}

export async function updateAppConfigLinks(
  req: LinkConfigurationsToAppRequest,
): Promise<void> {
  await apiClient.post('/private/applications/configurations', req);
}

// --- APK upload (drop → analyze → host) -------------------------------------
// See FilesResource (@Path /private/web-ui-files):
//   POST /                 multipart "file" -> FileUploadResult (parses the APK)
//   POST /update           commit the temp upload -> served file with a public url

/** Parsed APK metadata from the server's APKFileAnalyzer. */
export interface ApkFileDetails {
  pkg: string;
  name?: string;
  version?: string;
  versionCode?: number;
  arch?: string | null;
}

export interface FileUploadResult {
  name?: string;
  /** Absolute temp path on the server; pass back to commit. */
  serverPath: string;
  fileDetails?: ApkFileDetails;
  exists?: boolean;
  complete?: boolean;
}

export interface UploadedFileView {
  id?: number;
  /** Public, agent-reachable URL once committed. */
  url?: string;
  filePath?: string;
  fileName?: string;
}

/** Upload an APK; the server parses it and returns its package/version details. */
export async function uploadApk(file: File): Promise<FileUploadResult> {
  const form = new FormData();
  form.append('file', file, file.name);
  return apiClient.postForm<FileUploadResult>('/private/web-ui-files', form);
}

// --- Split-APK bundle upload (.xapk/.apks/.apkm/.zip) ------------------------
// The server unpacks the bundle, hosts each split, and returns the parts to
// install together in one session. See the /bundle endpoint on FilesResource.

export interface BundleUploadResult {
  name: string;
  packageName: string;
  version?: string;
  versionCode: number;
  parts: { url: string; sha256: string; name: string }[];
}

/** Upload a split-APK bundle; the server unpacks it and returns the hosted parts. */
export async function uploadBundle(file: File): Promise<BundleUploadResult> {
  const form = new FormData();
  form.append('file', file, file.name);
  return apiClient.postForm<BundleUploadResult>('/private/web-ui-files/bundle', form);
}

/** Commit a just-uploaded temp file into the served files area; returns its url. */
export async function commitUpload(serverPath: string, fileName = ''): Promise<UploadedFileView> {
  return apiClient.post<UploadedFileView>('/private/web-ui-files/update', {
    tmpPath: serverPath,
    fileName,
    filePath: fileName,
    external: false,
  });
}

/** A Play Store app looked up by package name or link (name + icon from its public page). */
export interface PlayApp { packageName: string; name: string; icon?: string | null }
export async function lookupPlayApp(q: string): Promise<PlayApp> {
  return apiClient.get<PlayApp>(`/private/applications/play?q=${encodeURIComponent(q)}`);
}

/**
 * A hosting name no other upload has: APKs are often all called app-release.apk, and the server refuses a name it
 * already hosts. Package and version first, so the files stay readable on the server.
 */
export function uniqueApkName(pkg: string | undefined, version: string | undefined, original: string): string {
  const clean = (v: string) => v.replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^_+|_+$/g, '');
  const base = clean(pkg || original.replace(/\.apk$/i, '')) || 'app';
  const ver = version ? `-${clean(version)}` : '';
  return `${base}${ver}-${Date.now().toString(36)}.apk`;
}

/**
 * Whether an uploaded APK is a new version of a Library app: a higher versionCode, or the same versionCode under a
 * version name the Library does not have yet (apps that only change the name between builds; the agent installs
 * those again by name).
 */
export function isNewVersion(versions: ApplicationVersion[], versionCode: number, versionName: string | undefined): boolean {
  const current = versions.reduce((m, v) => Math.max(m, v.versionCode ?? 0), 0);
  if (versionCode > current) return true;
  if (versionCode < current || !versionName) return false;
  return !versions.some((v) => (v.versionCode ?? 0) === versionCode && (v.version ?? '').trim() === versionName.trim());
}
