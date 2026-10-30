# Third-party code

Code ported into this repository from other projects, with its origin and licence.
Each ported file carries an attribution header naming the original file.

| Where in this repo | Origin | Licence | What changed |
|---|---|---|---|
| `app/src/main/kotlin/net/nuclearlighters/ventriloquist/vban/VbanPacket.kt` | `src/app/vbansender/VbanPacket.java` in [androidtv-vban-sender](https://github.com/zewelor/androidtv-vban-sender) | Apache-2.0 (`androidtv-vban-sender-LICENSE`) | Rewritten in Kotlin; sample rate and channel count are parameters instead of constants; the sample-rate index table is explicit |
| `app/src/test/kotlin/net/nuclearlighters/ventriloquist/vban/VbanPacketTest.kt` | `tests/ProtocolCheck.java` in the same project | Apache-2.0 | Golden header values kept; rewritten as JUnit tests |

The VBAN sender's source-clock pacing (`vban/VbanSender.kt`) was written for this project
rather than ported. A port of the test tone in `ToneSender.java` (same project, same licence)
may follow.
