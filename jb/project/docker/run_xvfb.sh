#!/bin/bash -x

Xvfb :0 -screen 0 1024x768x24 +extension RANDR &
bash