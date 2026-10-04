# Steps

The record of the work done in this repository, one line per step, in the order it happened. The rules for this file are in `AGENTS.md` under "Steps log".


- [x] 1. Add the JaCoCo plugin to measure coverage. Result: JaCoCo 0.8.14 wired into the pom; agent records `jacoco.exec` and `jacoco:report` writes `target/site/jacoco`; full `verify` not green yet because Docker is not running. (direct)
