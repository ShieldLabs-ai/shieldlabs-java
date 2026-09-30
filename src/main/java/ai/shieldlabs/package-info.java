/**
 * Server-side Java client for ShieldLabs.
 *
 * <p>The integration has three steps. The browser runs an identification with the ShieldLabs agent
 * and receives a request ID. Your backend receives that request ID with the protected action
 * (signup, login, checkout) and reads the verdict for it with
 * {@link ai.shieldlabs.ShieldLabsClient} (or receives it by a signed webhook, verified with
 * {@link ai.shieldlabs.Webhooks}). Your backend then acts on the Risk Score, the risk band and the
 * detection flags, for example with {@link ai.shieldlabs.Risk#evaluate(Identification, EvaluateOptions)}.
 *
 * <p>Entry points:
 *
 * <ul>
 *   <li>{@link ai.shieldlabs.ShieldLabsClient}: History API (verdict by request ID, lookups by device,
 *       visitor, user, IP, session or cookie).
 *   <li>{@link ai.shieldlabs.ManagementClient}: Management API (domain profile).
 *   <li>{@link ai.shieldlabs.Webhooks}: signature verification and typed webhook events.
 *   <li>{@link ai.shieldlabs.Risk}: risk bands and the protected-action guard.
 *   <li>{@link ai.shieldlabs.UserHid}: User HID creation on the server.
 * </ul>
 */
package ai.shieldlabs;
