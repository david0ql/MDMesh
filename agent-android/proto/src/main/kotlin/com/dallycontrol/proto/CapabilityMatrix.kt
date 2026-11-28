package com.dallycontrol.proto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Device -> server. Sent on enrollment and on every check-in.
 *
 * Advertises what this agent can *actually* do on this device, so the server only
 * ever issues commands it knows are supported. This is the concrete form of the
 * "evolvable / capability-gated" requirement: capabilities are open string
 * registries (see `proto/registry.md`), so a 3-year-old agent on Android 9 and a
 * fresh agent on Android 16 talk to the same server without special-casing.
 *
 * Mirrors `proto/capability-matrix.schema.json`.
 */
@Serializable
data class CapabilityMatrix(
    val protocolVersion: String = ProtocolJson.PROTOCOL_VERSION,
    val agent: AgentInfo,
    val device: DeviceInfo,
    val capabilities: Capabilities,
)

@Serializable
data class AgentInfo(
    val version: String,
    @SerialName("package") val packageName: String,
)

/**
 * Note: [id] is the **server-issued** opaque device id (see [com.dallycontrol.proto]
 * docs). It is NEVER an IMEI/IMSI/serial — those are restricted post-Android 10
 * and must not be used as identity.
 */
@Serializable
data class DeviceInfo(
    val id: String,
    val androidSdkInt: Int,
    val androidRelease: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val isDeviceOwner: Boolean,
)

@Serializable
data class Capabilities(
    /** Policy capability keys this device supports, e.g. `wifi`, `kioskLockTask`. */
    val policy: List<String> = emptyList(),
    /** App-management keys, e.g. `silentInstall`, `splitApk`. */
    val appManagement: List<String> = emptyList(),
    /** Device-action keys this agent supports, e.g. `lock`, `wipe`. Flattened server-side to `device.<key>`. */
    val device: List<String> = emptyList(),
    val remoteControl: RemoteControlCapability = RemoteControlCapability(),
    val oem: OemCapability = OemCapability(),
)

@Serializable
data class RemoteControlCapability(
    val tier: String = RemoteControlTier.NONE,
    val screenCapture: Boolean = false,
    val inputInjection: Boolean = false,
    val transport: List<String> = emptyList(),
    /** The agent's own support-session channel (ADR 0012); null on agents that predate it. */
    val support: SupportCapability? = null,
)

/**
 * Remote support through the agent itself (ADR 0012): screen, touch, voice, chat and files over one session channel.
 *
 * @property session the agent speaks the session channel (chat, voice and files work even without the screen).
 * @property screen `ready` (unattended view/control works), `needsAuthorization` (the one-time switch on the phone
 *   is pending), `unsupported` (Android too old) or `disabled` (the policy turned the agent's screen service off).
 * @property control touch and keys can be injected.
 * @property voice `ready` or `needsPermission` (microphone not granted).
 * @property capture `a11y` (accessibility screenshots, Android 11+), `projection` (screen-capture consent tapped by
 *   the agent's own service, Android 7-10) or `none`.
 * @property selfEnable the agent can switch its screen service on by itself (it holds WRITE_SECURE_SETTINGS).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SupportCapability(
    // Always written: the protocol's JSON drops defaults, and the server must read each of these as stated.
    @EncodeDefault val session: Boolean = true,
    @EncodeDefault val screen: String = SupportScreenState.UNSUPPORTED,
    @EncodeDefault val control: Boolean = false,
    @EncodeDefault val voice: String = "needsPermission",
    @EncodeDefault val capture: String = "none",
    @EncodeDefault val selfEnable: Boolean = false,
)

object SupportScreenState {
    const val READY = "ready"
    const val NEEDS_AUTHORIZATION = "needsAuthorization"
    const val UNSUPPORTED = "unsupported"
    const val DISABLED = "disabled"
}

/** Open string registry for remote-control tiers. See `proto/README.md`. */
object RemoteControlTier {
    const val NONE = "none"
    const val VIEW = "view"
    const val CONTROL = "control"
}

@Serializable
data class OemCapability(
    val vendor: String = "generic",
    val knox: Boolean = false,
)
