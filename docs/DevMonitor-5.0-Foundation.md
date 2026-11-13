# DevMonitor 5.0 — Project Foundation

**Status:** Foundation approved; implementation not yet started  
**Strategic direction:** Two editions, one unified DevMonitor ecosystem.

## 1. Central decision

DevMonitor 5.0 will offer two alternative editions. Each user chooses which edition to install and use. Users are not expected to install both editions on the same Android phone.

### DevMonitor Full

The existing edition, retaining native SMS capabilities:

- Sends SMS queries directly to 3030.
- Receives and processes SMS responses.
- Retains the existing SMS Checker workflow.
- Continues to support established reporting, history, MOM, Conversion, Google Drive and other existing features.

### DevMonitor Gateway

A second edition intended for users whose Android devices cannot successfully install or grant the required native SMS permissions:

- Does not declare or use native SMS sending or receiving permissions.
- Connects to DevMonitor Gateway infrastructure over the internet.
- Requests SMS checks through available dedicated gateway phones.
- Receives processed results from the gateway.
- Retains the essential DevMonitor reporting and management capabilities.

The Gateway edition is not a stripped-down application. It should offer the same core business functionality as Full edition, using a different SMS transport.

## 2. Shared application foundation

Both editions should use one shared application codebase wherever practical. Preserve:

- Device Monitor and My Partners.
- SMS Checker and its daily historical records.
- Loaded Reports and Online Company Reports.
- MOM and Conversion.
- Google Drive backup and synchronization.
- Existing payment calculations and established business rules.
- Existing user interface conventions and reporting tables.

The two editions should be separate Android build flavours/release targets, with distinct application IDs where needed for release management. They are user-selected alternatives, not companion apps.

## 3. Multi-phone gateway infrastructure

DevMonitor 5.0 will introduce a central gateway controller capable of coordinating multiple dedicated Android gateway phones.

The system should:

1. Register and monitor available gateway phones.
2. Distribute SMS requests among available phones according to availability and workload.
3. Prevent duplicate device queries and response confusion.
4. Maintain persistent queues and request histories.
5. Collect and consolidate responses.
6. Recover or safely reassign unfinished requests when a gateway disconnects.
7. Return results only to the correct authenticated DevMonitor user.

Gateway capacity should expand by adding more gateway phones.

## 4. Security and reliability

- Authenticate every gateway phone and DevMonitor client.
- Isolate each user's requests and results.
- Encrypt client/controller/gateway communications.
- Use unique request identifiers, idempotency and duplicate protection.
- Preserve the existing daily SMS history rules.
- Provide gateway availability and processing status.
- Prevent interrupted operations from corrupting saved results.
- Minimize stored personal and operational data.

## 5. Development strategy

Development should proceed on separate experimental branches.

- Preserve the existing Full edition as the working reference.
- Build Gateway edition without disturbing Full edition SMS functionality.
- Develop the gateway controller independently.
- Test with two or three existing working Android phones.
- Verify parallel processing, request correlation, retries, duplicate handling and recovery.
- Test Gateway edition installation and permissions on affected devices.
- Promote only tested changes after explicit approval.

No changes to the existing production application or main branch are authorized merely by approving this project direction.

## 6. Technical qualification

The Gateway edition should eliminate native SMS permission requirements from ordinary client installations. Dedicated gateway phones will still require the appropriate SMS permissions and Android installer authorization. Removing SMS permissions may improve installation accessibility, but cannot guarantee that Play Protect will never flag an application for another reason.

## 7. Definition of success

DevMonitor 5.0 should:

1. Preserve the dependable direct-SMS experience of DevMonitor Full.
2. Make DevMonitor Gateway installable on a wider range of Android devices without native SMS permissions.
3. Allow SMS Checker capacity to grow by adding gateway phones rather than requiring every user's phone to perform SMS operations.

**Guiding principle:** One DevMonitor ecosystem, two user-selected installation editions, and scalable SMS infrastructure.
