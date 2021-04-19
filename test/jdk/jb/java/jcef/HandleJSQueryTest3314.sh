Last login: Mon Apr  5 14:18:38 on ttys000

The default interactive shell is now zsh.
To update your account to use zsh, please run `chsh -s /bin/zsh`.
For more details, please visit https://support.apple.com/kb/HT208050.
unit-929:~ vprovodin$ ssh builduser@172.31.150.161
The authenticity of host '172.31.150.161 (172.31.150.161)' can't be established.
ECDSA key fingerprint is SHA256:rB5sroaDkBkuaA0PWmxwK7zkqfgoo3ArkI4wToKK8TY.
Are you sure you want to continue connecting (yes/no/[fingerprint])? yes
Warning: Permanently added '172.31.150.161' (ECDSA) to the list of known hosts.
Welcome to Ubuntu 20.04.1 LTS (GNU/Linux 5.4.0-56-generic x86_64)

 * Documentation:  https://help.ubuntu.com
 * Management:     https://landscape.canonical.com
 * Support:        https://ubuntu.com/advantage

0 updates can be installed immediately.
0 of these updates are security updates.


The list of available updates is more than a week old.
To check for new updates run: sudo apt update
builduser@intellij-ubuntu2004-jre-302:~$ sudo apt-get install gnome-session gdm3
[sudo] password for builduser:
Reading package lists... Done
Building dependency tree
Reading state information... Done
Package gnome-session is not available, but is referred to by another package.
This may mean that the package is missing, has been obsoleted, or
is only available from another source
However the following packages replace it:
  ubuntu-session

E: Package 'gnome-session' has no installation candidate
builduser@intellij-ubuntu2004-jre-302:~$ sudo apt-get install gnome-terminal
Reading package lists... Done
Building dependency tree
Reading state information... Done
Package gnome-terminal is not available, but is referred to by another package.
This may mean that the package is missing, has been obsoleted, or
is only available from another source

E: Package 'gnome-terminal' has no installation candidate
builduser@intellij-ubuntu2004-jre-302:~$ sudo apt install gnome-terminal
Reading package lists... Done
Building dependency tree
Reading state information... Done
Package gnome-terminal is not available, but is referred to by another package.
This may mean that the package is missing, has been obsoleted, or
is only available from another source

E: Package 'gnome-terminal' has no installation candidate
builduser@intellij-ubuntu2004-jre-302:~$ sudo apt-get update
Get:1 http://security.ubuntu.com/ubuntu focal-security InRelease [109 kB]
Get:2 http://ppa.launchpad.net/git-core/ppa/ubuntu focal InRelease [23.8 kB]
Get:3 http://download.mono-project.com/repo/ubuntu focal InRelease [4416 B]
Get:4 https://download.docker.com/linux/ubuntu focal InRelease [36.2 kB]
Get:5 http://ppa.launchpad.net/ondrej/php/ubuntu focal InRelease [23.9 kB]
Get:6 http://download.mono-project.com/repo/ubuntu focal/main amd64 Packages [46.8 kB]
Get:7 http://us.archive.ubuntu.com/ubuntu focal InRelease [265 kB]
Get:8 https://download.docker.com/linux/ubuntu focal/stable amd64 Packages [8458 B]
Get:9 https://packages.microsoft.com/repos/microsoft-ubuntu-focal-prod focal InRelease [10.5 kB]
Get:10 http://ppa.launchpad.net/git-core/ppa/ubuntu focal/main amd64 Packages [3044 B]
Get:11 http://security.ubuntu.com/ubuntu focal-security/main amd64 Packages [575 kB]
Get:12 http://ppa.launchpad.net/git-core/ppa/ubuntu focal/main i386 Packages [3048 B]
Get:13 http://ppa.launchpad.net/ondrej/php/ubuntu focal/main amd64 Packages [91.8 kB]
Get:14 http://security.ubuntu.com/ubuntu focal-security/main i386 Packages [210 kB]
Get:15 http://security.ubuntu.com/ubuntu focal-security/main amd64 c-n-f Metadata [7436 B]
Get:16 http://security.ubuntu.com/ubuntu focal-security/restricted i386 Packages [14.9 kB]
Get:17 http://security.ubuntu.com/ubuntu focal-security/restricted amd64 Packages [148 kB]
Get:18 http://security.ubuntu.com/ubuntu focal-security/restricted amd64 c-n-f Metadata [392 B]
Get:19 http://security.ubuntu.com/ubuntu focal-security/universe amd64 Packages [551 kB]
Get:20 http://security.ubuntu.com/ubuntu focal-security/universe i386 Packages [436 kB]
Get:21 http://security.ubuntu.com/ubuntu focal-security/universe amd64 c-n-f Metadata [10.7 kB]
Get:22 https://packages.microsoft.com/repos/microsoft-ubuntu-focal-prod focal/main amd64 Packages [64.0 kB]
Get:23 http://ppa.launchpad.net/ondrej/php/ubuntu focal/main i386 Packages [29.8 kB]
Get:24 http://us.archive.ubuntu.com/ubuntu focal-updates InRelease [114 kB]
Get:25 http://us.archive.ubuntu.com/ubuntu focal-backports InRelease [101 kB]
Get:26 http://us.archive.ubuntu.com/ubuntu focal/main i386 Packages [718 kB]
Get:27 http://us.archive.ubuntu.com/ubuntu focal/main amd64 Packages [970 kB]
Get:28 http://us.archive.ubuntu.com/ubuntu focal/main amd64 c-n-f Metadata [29.5 kB]
Get:29 http://us.archive.ubuntu.com/ubuntu focal/restricted i386 Packages [8112 B]
Get:30 http://us.archive.ubuntu.com/ubuntu focal/restricted amd64 Packages [22.0 kB]
Get:31 http://us.archive.ubuntu.com/ubuntu focal/restricted amd64 c-n-f Metadata [392 B]
Get:32 http://us.archive.ubuntu.com/ubuntu focal/universe i386 Packages [4642 kB]
Get:33 http://us.archive.ubuntu.com/ubuntu focal/universe amd64 Packages [8628 kB]
Get:34 http://us.archive.ubuntu.com/ubuntu focal/universe amd64 c-n-f Metadata [265 kB]
Get:35 http://us.archive.ubuntu.com/ubuntu focal-updates/main amd64 Packages [895 kB]
Get:36 http://us.archive.ubuntu.com/ubuntu focal-updates/main i386 Packages [449 kB]
Get:37 http://us.archive.ubuntu.com/ubuntu focal-updates/main amd64 c-n-f Metadata [13.0 kB]
Get:38 http://us.archive.ubuntu.com/ubuntu focal-updates/restricted i386 Packages [16.2 kB]
Get:39 http://us.archive.ubuntu.com/ubuntu focal-updates/restricted amd64 Packages [171 kB]
Get:40 http://us.archive.ubuntu.com/ubuntu focal-updates/restricted amd64 c-n-f Metadata [436 B]
Get:41 http://us.archive.ubuntu.com/ubuntu focal-updates/universe amd64 Packages [758 kB]
Get:42 http://us.archive.ubuntu.com/ubuntu focal-updates/universe i386 Packages [561 kB]
Get:43 http://us.archive.ubuntu.com/ubuntu focal-updates/universe amd64 c-n-f Metadata [16.5 kB]
Get:44 http://us.archive.ubuntu.com/ubuntu focal-backports/main amd64 c-n-f Metadata [112 B]
Get:45 http://us.archive.ubuntu.com/ubuntu focal-backports/restricted amd64 c-n-f Metadata [116 B]
Get:46 http://us.archive.ubuntu.com/ubuntu focal-backports/universe i386 Packages [2932 B]
Get:47 http://us.archive.ubuntu.com/ubuntu focal-backports/universe amd64 Packages [4032 B]
Get:48 http://us.archive.ubuntu.com/ubuntu focal-backports/universe amd64 c-n-f Metadata [224 B]
Fetched 21.1 MB in 4s (5664 kB/s)
Reading package lists... Done
N: Skipping acquire of configured file 'main/binary-i386/Packages' as repository 'http://download.mono-project.com/repo/ubuntu focal InRelease' doesn't support architecture 'i386'
builduser@intellij-ubuntu2004-jre-302:~$ sudo apt install gnome-terminal
Reading package lists... Done
Building dependency tree
Reading state information... Done
The following additional packages will be installed:
  gnome-terminal-data nautilus-extension-gnome-terminal
The following NEW packages will be installed:
  gnome-terminal gnome-terminal-data nautilus-extension-gnome-terminal
0 upgraded, 3 newly installed, 0 to remove and 407 not upgraded.
Need to get 233 kB of archives.
After this operation, 2236 kB of additional disk space will be used.
Do you want to continue? [Y/n] Y
Get:1 http://us.archive.ubuntu.com/ubuntu focal-updates/main amd64 gnome-terminal-data all 3.36.2-1ubuntu1~20.04 [30.3 kB]
Get:2 http://us.archive.ubuntu.com/ubuntu focal-updates/main amd64 gnome-terminal amd64 3.36.2-1ubuntu1~20.04 [171 kB]
Get:3 http://us.archive.ubuntu.com/ubuntu focal-updates/main amd64 nautilus-extension-gnome-terminal amd64 3.36.2-1ubuntu1~20.04 [32.2 kB]
Fetched 233 kB in 1s (323 kB/s)
Selecting previously unselected package gnome-terminal-data.
(Reading database ... 112447 files and directories currently installed.)
Preparing to unpack .../gnome-terminal-data_3.36.2-1ubuntu1~20.04_all.deb ...
Unpacking gnome-terminal-data (3.36.2-1ubuntu1~20.04) ...
Selecting previously unselected package gnome-terminal.
Preparing to unpack .../gnome-terminal_3.36.2-1ubuntu1~20.04_amd64.deb ...
Unpacking gnome-terminal (3.36.2-1ubuntu1~20.04) ...
Selecting previously unselected package nautilus-extension-gnome-terminal.
Preparing to unpack .../nautilus-extension-gnome-terminal_3.36.2-1ubuntu1~20.04_amd64.deb ...
Unpacking nautilus-extension-gnome-terminal (3.36.2-1ubuntu1~20.04) ...
Setting up gnome-terminal-data (3.36.2-1ubuntu1~20.04) ...
Setting up gnome-terminal (3.36.2-1ubuntu1~20.04) ...
update-alternatives: using /usr/bin/gnome-terminal.wrapper to provide /usr/bin/x-terminal-emulator (x-terminal-emulator) in auto mode
Processing triggers for desktop-file-utils (0.24-1ubuntu3) ...
Processing triggers for mime-support (3.64ubuntu1) ...
Processing triggers for hicolor-icon-theme (0.17-2) ...
Processing triggers for gnome-menus (3.36.0-1ubuntu1) ...
Processing triggers for libglib2.0-0:amd64 (2.64.3-1~ubuntu20.04.1) ...
Processing triggers for man-db (2.9.1-1) ...
Setting up nautilus-extension-gnome-terminal (3.36.2-1ubuntu1~20.04) ...
builduser@intellij-ubuntu2004-jre-302:~$ wget
wget: missing URL
Usage: wget [OPTION]... [URL]...

Try `wget --help' for more options.
builduser@intellij-ubuntu2004-jre-302:~$  wget https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
--2021-04-08 09:49:43--  https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
Resolving dl.google.com (dl.google.com)... 64.233.161.136, 64.233.161.93, 64.233.161.91, ...
Connecting to dl.google.com (dl.google.com)|64.233.161.136|:443... connected.
HTTP request sent, awaiting response... 200 OK
Length: 75857544 (72M) [application/x-debian-package]
Saving to: ‘google-chrome-stable_current_amd64.deb’

google-chrome-stable_current_amd64.deb   100%[=================================================================================>]  72.34M   140MB/s    in 0.5s

2021-04-08 09:49:44 (140 MB/s) - ‘google-chrome-stable_current_amd64.deb’ saved [75857544/75857544]

builduser@intellij-ubuntu2004-jre-302:~$ sudo dpkg -i google-chrome-stable_current_amd64.deb
Selecting previously unselected package google-chrome-stable.
(Reading database ... 113007 files and directories currently installed.)
Preparing to unpack google-chrome-stable_current_amd64.deb ...
Unpacking google-chrome-stable (89.0.4389.114-1) ...
dpkg: dependency problems prevent configuration of google-chrome-stable:
 google-chrome-stable depends on fonts-liberation; however:
  Package fonts-liberation is not installed.

dpkg: error processing package google-chrome-stable (--install):
 dependency problems - leaving unconfigured
Processing triggers for gnome-menus (3.36.0-1ubuntu1) ...
Processing triggers for desktop-file-utils (0.24-1ubuntu3) ...
Processing triggers for mime-support (3.64ubuntu1) ...
Processing triggers for man-db (2.9.1-1) ...
Errors were encountered while processing:
 google-chrome-stable
builduser@intellij-ubuntu2004-jre-302:~$ ls -l
total 74124
drwxr-xr-x  2 builduser builduser     4096 Dec  3 14:28 Desktop
drwxr-xr-x  2 builduser builduser     4096 Dec  3 14:28 Documents
drwxr-xr-x  2 builduser builduser     4096 Dec  3 14:28 Downloads
drwxr-xr-x  2 builduser builduser     4096 Dec  3 14:28 Music
drwxr-xr-x  2 builduser builduser     4096 Dec  3 14:28 Pictures
drwxr-xr-x  2 builduser builduser     4096 Dec  3 14:28 Public
drwxr-xr-x  2 builduser builduser     4096 Dec  3 14:28 Templates
drwxr-xr-x  2 builduser builduser     4096 Dec  3 14:28 Videos
-rw-r--r--  1 builduser builduser      230 Dec  3 14:29 agent-starter.sh
drwxrwxr-x 10 builduser builduser     4096 Dec  3 10:38 android-sdk-linux
drwxrwxr-x  4 builduser builduser     4096 Dec  3 10:26 go
-rw-rw-r--  1 builduser builduser 75857544 Mar 29 21:39 google-chrome-stable_current_amd64.deb
builduser@intellij-ubuntu2004-jre-302:~$ rm -rf google-chrome-stable_current_amd64.deb
builduser@intellij-ubuntu2004-jre-302:~$ sudo apt install gnome-shell
Reading package lists... Done
Building dependency tree
Reading state information... Done
You might want to run 'apt --fix-broken install' to correct these.
The following packages have unmet dependencies:
 gnome-shell : Depends: gnome-shell-common (= 3.36.7-0ubuntu0.20.04.1) but 3.36.4-1ubuntu1~20.04.2 is to be installed
               Recommends: bolt (>= 0.3) but it is not going to be installed
               Recommends: gkbd-capplet but it is not going to be installed
               Recommends: gnome-user-docs but it is not going to be installed
               Recommends: ibus
               Recommends: iio-sensor-proxy but it is not going to be installed
               Recommends: switcheroo-control but it is not going to be installed
               Recommends: xserver-xorg-legacy but it is not going to be installed
 google-chrome-stable : Depends: fonts-liberation but it is not going to be installed
E: Unmet dependencies. Try 'apt --fix-broken install' with no packages (or specify a solution).
builduser@intellij-ubuntu2004-jre-302:~$ client_loop: send disconnect: Broken pipe
unit-929:~ vprovodin$ ssh -L 5901:localhost:5900 builduser@jre-rpi4b8g-00.myo.labs.intellij.net
Welcome to Ubuntu 20.10 (GNU/Linux 5.8.0-1019-raspi aarch64)

 * Documentation:  https://help.ubuntu.com
 * Management:     https://landscape.canonical.com
 * Support:        https://ubuntu.com/advantage

  System information as of Mon Apr 12 06:54:32 UTC 2021

  System load:  1.34               Processes:                206
  Usage of /:   4.5% of 458.15GB   Users logged in:          1
  Memory usage: 20%                IPv4 address for docker0: 10.192.0.1
  Swap usage:   0%                 IPv4 address for eth0:    172.29.28.131
  Temperature:  35.5 C

  => There is 1 zombie process.

 * Introducing self-healing high availability clusters in MicroK8s.
   Simple, hardened, Kubernetes for production, from RaspberryPi to DC.

     https://microk8s.io/high-availability

11 updates can be installed immediately.
0 of these updates are security updates.
To see these additional updates run: apt list --upgradable


Last login: Mon Mar 29 08:55:33 2021 from 172.25.9.1
builduser@jre-rpi4b8g-00:~$ exit
logout
Connection to jre-rpi4b8g-00.myo.labs.intellij.net closed.
unit-929:~ vprovodin$ ssh -L 5901:localhost:5900 builduser@jre-rpi4b8g-01.myo.labs.intellij.net
Welcome to Ubuntu 20.10 (GNU/Linux 5.8.0-1019-raspi aarch64)

 * Documentation:  https://help.ubuntu.com
 * Management:     https://landscape.canonical.com
 * Support:        https://ubuntu.com/advantage

  System information as of Mon Apr 12 06:56:27 UTC 2021

  System load:  0.25               Processes:                203
  Usage of /:   3.9% of 458.15GB   Users logged in:          1
  Memory usage: 18%                IPv4 address for docker0: 10.192.0.1
  Swap usage:   0%                 IPv4 address for eth0:    172.29.28.132
  Temperature:  38.0 C

 * Introducing self-healing high availability clusters in MicroK8s.
   Simple, hardened, Kubernetes for production, from RaspberryPi to DC.

     https://microk8s.io/high-availability

11 updates can be installed immediately.
0 of these updates are security updates.
To see these additional updates run: apt list --upgradable


Last login: Sat Mar 27 09:21:00 2021 from 172.29.20.53
builduser@jre-rpi4b8g-01:~$ sudo apt-get update
[sudo] password for builduser:
Sorry, try again.
[sudo] password for builduser:
Sorry, try again.
[sudo] password for builduser:
sudo: 2 incorrect password attempts
builduser@jre-rpi4b8g-01:~$ sudo -
[sudo] password for builduser:
Sorry, try again.
[sudo] password for builduser:
sudo: 1 incorrect password attempt
builduser@jre-rpi4b8g-01:~$ chpasswd
^C
builduser@jre-rpi4b8g-01:~$ client_loop: send disconnect: Broken pipe
unit-929:~ vprovodin$
  [Restored 13 Apr 2021, 12:08:55]
Last login: Tue Apr 13 12:08:55 on ttys000
Restored session: Tue Apr 13 12:08:29 +07 2021

The default interactive shell is now zsh.
To update your account to use zsh, please run `chsh -s /bin/zsh`.
For more details, please visit https://support.apple.com/kb/HT208050.
unit-929:~ vprovodin$
  [Restored 15 Apr 2021, 10:26:44]
Last login: Thu Apr 15 10:26:44 on ttys004
Restored session: Thu Apr 15 10:26:08 +07 2021

The default interactive shell is now zsh.
To update your account to use zsh, please run `chsh -s /bin/zsh`.
For more details, please visit https://support.apple.com/kb/HT208050.
unit-929:~ vprovodin$ exit
logout
Saving session...
...saving history...truncating history files...
...completed.

[Process completed]

  [Restored 15 Apr 2021, 11:31:08]
Last login: Thu Apr 15 11:31:08 on ttys000
Restored session: Thu Apr 15 11:12:00 +07 2021

The default interactive shell is now zsh.
To update your account to use zsh, please run `chsh -s /bin/zsh`.
For more details, please visit https://support.apple.com/kb/HT208050.
unit-929:~ vprovodin$ history | grep ssh
   61  cat 5F7781BB35451900.pub_ssh
  200  history | grep ssh
  201  ssh -L 5901:localhost:5900 builduser@unit-2181
  202  ssh -L 5901:localhost:5900 builduser@unit-2182
  203  ssh -L 5901:localhost:5900 builduser@unit-2181
  204  ssh -L 5901:localhost:5900 builduser@unit-2182
  206  ssh -L 5901:localhost:5900 builduser@unit-2181
  208  ssh -L 5901:localhost:5900 builduser@unit-2181
  209  ssh -L 5901:localhost:5900 builduser@munit-430.myo.labs.intellij.net
  210  ssh -L 5901:localhost:5900 builduser@munit-429.myo.labs.intellij.net
  211  ssh -L 5901:localhost:5900 builduser@unit-2181
  213  ssh -L 5901:localhost:5900 builduser@munit-430.myo.labs.intellij.net
  214  ssh -L 5901:localhost:5900 builduser@munit-429.myo.labs.intellij.net
  215  ssh -L 5901:localhost:5900 builduser@unit-2181
  216  ssh -L 5901:localhost:5900 builduser@unit-2182
  218  ssh -L 5901:localhost:5900 builduser@jre-rpi4b8g-00.myo.labs.intellij.net
  219  ssh -L 5901:localhost:5900 builduser@jre-rpi4b8g-01.myo.labs.intellij.net
  302  ssh -L 5901:localhost:5900 builduser@munit-430.myo.labs.intellij.net
  304  ssh -L 5901:localhost:5900 builduser@munit-430.myo.labs.intellij.net
  305  ssh -L 5901:localhost:5900 builduser@munit-430.myo.labs.intellij.net
  306  ssh -L 5901:localhost:5900 builduser@munit-430.myo.labs.intellij.net
  307  ssh -L 5901:localhost:5900 builduser@munit-430.myo.labs.intellij.net
  308  ssh builduser:intellij-fedora25
  309  ssh builduser:intellij-fedora25
  310  ssh builduser:
  311  ssh builduser@intellij-fedora25
  312  ssh builduser@intellij-fedora25
  313  ssh builduser@intellij-fedora25
  314  ssh builduser@intellij-ubuntu1804-jre-111
  330  ssh builduser@unit-417
  332  ssh builduser@unit-417
  334  ssh builduser@unit-417
  363  ssh builduser@intellij-archlinux-jre
  364  ssh builduser@jre-archlinux
  369  ssh builduser@intellij-archlinux-jre
  371  ssh builduser@intellij-archlinux-jre
  373  ssh builduser@intellij-archlinux-jre
  388  ssh builduser@intellij-ubuntu2004-jre-284
  394  ssh builduser@unit-417
  395  ssh builduser@unit-828
  396  ssh builduser@intellij-fedora25
  397  ssh builduser@unit-828
  399  ssh builduser@intellij-archlinux-jre
  400  ssh builduser@intellij-archlinux-jre
  401  ssh builduser@intellij-archlinux-jre
  402  ssh builduser@intellij-archlinux-jre
  403  ssh builduser@intellij-archlinux-jre-1
  404  ssh builduser@intellij-ubuntu1804-115
  408  ssh builduser@intellij-ubuntu1804-115
  411  ssh builduser@intellij-archlinux-jre
  412  ssh builduser@intellij-archlinux-jre
  416  ssh builduser@intellij-ubuntu1804-jre-115
  417  ssh builduser@intellij-ubuntu1804-jre-115
  418  ssh -X intellij-archlinux-jre
  419  ssh -X builduser@intellij-archlinux-jre
  434  ssh builduser@unit-828
  435  ssh builduser@intellij-archlinux-jre
  436  ssh builduser@172.31.135.98
  441  ssh -X builduser@intellij-archlinux-jre
  444  ssh builduser@unit-417
  445  ssh builduser@unit-417
  447  ssh -L 5901:localhost:5900 builduser@munit-429.myo.labs.intellij.net
  448  ssh -L 5901:localhost:5900 builduser@munit-429.myo.labs.intellij.net
  449  ssh builduser@macbuilder-esxi.labs.intellij.net
  466  ssh builduser@172.31.129.216
  468  ssh builduser@unit-1074
  470  history | grep ssh
  471  ssh -L 5901:localhost:5900 builduser@munit-429.myo.labs.intellij.net
  472  ssh -L 5901:localhost:5900 builduser@mjre-linux-arm64-hw-rpi4b8g-02
  473  ssh -L 5901:localhost:5900 builduser@jre-linux-arm64-hw-rpi4b8g-02
  474  ssh -L 5901:localhost:5900 builduser@jre-linux-arm64-hw-rpi4b8g-02.myo.labs.intellij.net
  476  history | grep ssh
  477  ssh -L 5901:localhost:5900 builduser@unit-2182
  478  ssh -L 5901:localhost:5900 builduser@unit-2181
  482  ssh -L 5901:localhost:5900 builduser@munit-430.myo.labs.intellij.net
  483  ssh -L 5901:localhost:5900 builduser@munit-429.myo.labs.intellij.net
  490  ssh -L 5901:localhost:5900 builduser@jre-linux-arm64-hw-rpi4b8g-00
  491  ssh -L 5901:localhost:5900 builduser@jre-linux-arm64-hw-rpi4b8g-00.myo.labs.intellij.net
  492  ssh -L 5901:localhost:5900 builduser@jre-rpi4b8g-00.myo.labs.intellij.net
  499  ssh builduser@172.31.150.161
  500  ssh -L 5901:localhost:5900 builduser@jre-rpi4b8g-00.myo.labs.intellij.net
  501  ssh -L 5901:localhost:5900 builduser@jre-rpi4b8g-01.myo.labs.intellij.net
  503  history | grep ssh
unit-929:~ vprovodin$ ssh -L 5901:localhost:5900 builduser@unit-2182
Last login: Wed Mar 31 08:46:53 2021

The default interactive shell is now zsh.
To update your account to use zsh, please run `chsh -s /bin/zsh`.
For more details, please visit https://support.apple.com/kb/HT208050.
unit-2182:~ builduser$ exit
logout
^[[A^Cunit-929:~ vprovodin$ ssh -L 5901:localhost:5900 builduser@unit-2181
Last login: Mon Apr  5 14:42:05 2021 from 172.20.6.239

The default interactive shell is now zsh.
To update your account to use zsh, please run `chsh -s /bin/zsh`.
For more details, please visit https://support.apple.com/kb/HT208050.
unit-2181:~ builduser$ exit
logout
^Cunit-929:~ vprovodin$ ssh -L 5901:localhost:5900 builduser@munit-430.myo.labs.intellij.net
Last login: Wed Mar 31 02:13:31 2021 from 172.25.9.1

The default interactive shell is now zsh.
To update your account to use zsh, please run `chsh -s /bin/zsh`.
For more details, please visit https://support.apple.com/kb/HT208050.
munit-430:~ builduser$ exit
logout
^Cunit-929:~ vprovodin$ ssh -L 5901:localhost:5900 builduser@jre-linux-arm64-hw-rpi4b8g-00.myo.labs.intellij.net
unit-929:~ vprovodin$ ssh -L 5901:localhost:5900 builduser@jre-rpi4b8g-00.myo.labs.intellij.net
Welcome to Ubuntu 20.10 (GNU/Linux 5.8.0-1019-raspi aarch64)

 * Documentation:  https://help.ubuntu.com
 * Management:     https://landscape.canonical.com
 * Support:        https://ubuntu.com/advantage

  System information as of Sun Apr 18 23:48:40 UTC 2021

  System load:  1.22               Processes:                206
  Usage of /:   5.1% of 458.15GB   Users logged in:          1
  Memory usage: 19%                IPv4 address for docker0: 10.192.0.1
  Swap usage:   1%                 IPv4 address for eth0:    172.29.28.131
  Temperature:  36.5 C

  => There is 1 zombie process.

 * Introducing self-healing high availability clusters in MicroK8s.
   Simple, hardened, Kubernetes for production, from RaspberryPi to DC.

     https://microk8s.io/high-availability

17 updates can be installed immediately.
0 of these updates are security updates.
To see these additional updates run: apt list --upgradable


*** System restart required ***
Last login: Mon Apr 12 06:54:34 2021 from 172.25.9.2
builduser@jre-rpi4b8g-00:~$ ps -ufx
USER         PID %CPU %MEM    VSZ   RSS TTY      STAT START   TIME COMMAND
buildus+ 1312364  0.0  0.0  16584  4432 ?        S    23:48   0:00 sshd: builduser@pts/0
buildus+ 1312366  0.7  0.0   9736  4628 pts/0    Ss   23:48   0:00  \_ -bash
buildus+ 1312394  0.0  0.0  12136  3176 pts/0    R+   23:48   0:00      \_ ps -ufx
buildus+ 1312365  3.5  0.1  33428 11968 ?        Ss   23:48   0:00 x11vnc -inetd -o /var/log/x11vnc.log -localhost -display :0
buildus+    2061  0.0  0.0 164188  5480 tty2     Ssl+ Mar29   0:00 /usr/libexec/gdm-x-session --run-script env GNOME_SHELL_SESSION_MODE=ubuntu /usr/bin/gnome-sessi
buildus+    2068  0.1  0.9 247176 76612 tty2     Sl+  Mar29  49:22  \_ /usr/lib/xorg/Xorg vt2 -displayfd 3 -auth /run/user/1002/gdm/Xauthority -nolisten tcp -backg
buildus+    2429  0.0  0.1 188648 10624 tty2     Sl+  Mar29   0:00  \_ /usr/libexec/gnome-session-binary --systemd --session=ubuntu
buildus+    2719  0.0  0.0   5348  1096 ?        Ss   Mar29   0:15      \_ /usr/bin/ssh-agent /usr/bin/im-launch env GNOME_SHELL_SESSION_MODE=ubuntu /usr/bin/gnome
buildus+  322474  0.1  0.6 2931912 49748 ?       Sl   Apr17   4:45 /usr/lib/jvm/java-11-amazon-corretto/bin/java -ea -Xms16m -Xmx64m -cp ../launcher/lib/launcher.j
buildus+  322499 35.6  5.6 4271360 438788 ?      Sl   Apr17 988:48  \_ /usr/lib/jvm/java-11-amazon-corretto/bin/java -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPa
buildus+ 1156051  0.1  0.0      0     0 ?        Zl   Mar30  53:22 [java] <defunct>
buildus+  199148  0.0  0.0   8528  3000 ?        Ss   Mar30   0:05 /usr/bin/dbus-daemon --syslog-only --fork --print-pid 5 --print-address 7 --session
buildus+  199146  0.0  0.0   7848  1644 ?        S    Mar30   0:00 dbus-launch --autolaunch=c8617ab7a85f43cf9ccb65d7859c21fc --binary-syntax --close-stderr
buildus+    2047  0.0  0.0 239436  6048 ?        Sl   Mar29   0:00 /usr/bin/gnome-keyring-daemon --daemonize --login
buildus+    1920  0.0  0.1  20740  9364 ?        Ss   Mar29   0:08 /lib/systemd/systemd --user
buildus+    1921  0.0  0.0 170968  1992 ?        S    Mar29   0:00  \_ (sd-pam)
buildus+    2026  0.0  0.1 1145020 12528 ?       Ssl  Mar29   0:01  \_ /usr/bin/pulseaudio --daemonize=no --log-target=journal
buildus+    2028  0.0  0.2 512288 16196 ?        SNsl Mar29   0:01  \_ /usr/libexec/tracker-miner-fs
buildus+    2050  0.0  0.0   9796  4916 ?        Ss   Mar29   0:09  \_ /usr/bin/dbus-daemon --session --address=systemd: --nofork --nopidfile --systemd-activation
buildus+    2083  0.0  0.0 239208  6432 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gvfsd
buildus+    3138  0.0  0.1 313400  7972 ?        Sl   Mar29   0:01  |   \_ /usr/libexec/gvfsd-trash --spawner :1.4 /org/gtk/gvfs/exec_spaw/0
buildus+    2094  0.0  0.1 315952 12996 ?        Ssl  Mar29   0:12  \_ /usr/libexec/gvfs-udisks2-volume-monitor
buildus+    2901  0.0  0.0 305868  5360 ?        Ssl  Mar29   0:00  \_ /usr/libexec/at-spi-bus-launcher
buildus+    2906  0.0  0.0   8660  3836 ?        S    Mar29   0:03  |   \_ /usr/bin/dbus-daemon --config-file=/usr/share/defaults/at-spi2/accessibility.conf --nofo
buildus+    2919  0.0  0.0  90184  3708 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gnome-session-ctl --monitor
buildus+    2926  0.0  0.1 558124 12016 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gnome-session-binary --systemd-service --session=ubuntu
buildus+    3239  0.0  0.4 632324 38964 ?        Sl   Mar29   0:07  |   \_ /usr/libexec/evolution-data-server/evolution-alarm-notify
buildus+    4220  0.0  0.2 413724 23044 ?        Sl   Mar29   1:36  |   \_ update-notifier
buildus+    2967  0.3  4.8 3691468 382612 ?      Ssl  Mar29  97:40  \_ /usr/bin/gnome-shell
buildus+    3079  0.0  0.0 235040  5372 ?        Ssl  Mar29   0:00  \_ /usr/libexec/xdg-permission-store
buildus+    3084  0.0  0.1 582972 12628 ?        Sl   Mar29   0:00  \_ /usr/libexec/gnome-shell-calendar-server
buildus+    3090  0.0  0.2 1073040 18880 ?       Ssl  Mar29   0:00  \_ /usr/libexec/evolution-source-registry
buildus+    3099  0.0  0.2 841784 20080 ?        Ssl  Mar29   0:00  \_ /usr/libexec/evolution-calendar-factory
buildus+    3109  0.0  0.0 156572  5280 ?        Sl   Mar29   0:00  \_ /usr/libexec/dconf-service
buildus+    3110  0.0  0.2 675680 19176 ?        Ssl  Mar29   0:00  \_ /usr/libexec/evolution-addressbook-factory
buildus+    3165  0.0  0.2 2733560 21492 ?       Sl   Mar29   0:00  \_ /usr/bin/gjs /usr/share/gnome-shell/org.gnome.Shell.Notifications
buildus+    3168  0.1  0.0 162456  7556 ?        Sl   Mar29  31:44  \_ /usr/libexec/at-spi2-registryd --use-gnome-session
buildus+    3184  0.0  0.0 309120  5800 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gsd-a11y-settings
buildus+    3185  0.0  0.2 562116 21468 ?        Ssl  Mar29   0:19  \_ /usr/libexec/gsd-color
buildus+    3188  0.0  0.1 374712 13912 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gsd-datetime
buildus+    3189  0.0  0.0 310736  6684 ?        Ssl  Mar29   1:54  \_ /usr/libexec/gsd-housekeeping
buildus+    3198  0.0  0.2 338796 21072 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gsd-keyboard
buildus+    3200  0.0  0.2 893580 22808 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gsd-media-keys
buildus+    3204  0.0  0.2 340508 21556 ?        Ssl  Mar29   0:06  \_ /usr/libexec/gsd-power
buildus+    3205  0.0  0.1 322580  9132 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gsd-print-notifications
buildus+    3206  0.0  0.0 456668  5664 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gsd-rfkill
buildus+    3207  0.0  0.0 234996  5548 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gsd-screensaver-proxy
buildus+    3211  0.0  0.1 464804  8136 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gsd-sharing
buildus+    3216  0.0  0.0 314308  7228 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gsd-smartcard
buildus+    3222  0.0  0.1 317684  8084 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gsd-sound
buildus+    3226  0.0  0.1 343008 11912 ?        Sl   Mar29   0:00  \_ /usr/libexec/gsd-printer
buildus+    3233  0.0  0.2 338268 20024 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gsd-wacom
buildus+    3237  0.0  0.2 338528 21000 ?        Ssl  Mar29   0:01  \_ /usr/libexec/gsd-xsettings
buildus+    4217  0.0  0.0 161784  5744 ?        Ssl  Mar29   0:00  \_ /usr/libexec/gvfsd-metadata
buildus+  159165  0.0  1.4 540932 111192 ?       SNl  Apr14   0:08  \_ /usr/bin/python3 /usr/bin/update-manager --no-update --no-focus-on-map
buildus+ 1312380 10.7  0.5 143672 43772 ?        Ss   23:48   0:01  \_ /usr/bin/python3 /usr/lib/ubuntu-release-upgrader/check-new-release-gtk
buildus+    1816  0.0  0.0   2228  1268 ?        Ss   Mar29   0:00 /bin/sh /opt/buildAgent/bin/agent.sh run
buildus+    2931  0.1  0.6 2999496 53324 ?       Sl   Mar29  39:57  \_ /usr/lib/jvm/java-11-amazon-corretto/bin/java -ea -Xms16m -Xmx64m -cp ../launcher/lib/launch
builduser@jre-rpi4b8g-00:~$ ps -ufx | grep java
buildus+ 1312396  0.0  0.0   7732   828 pts/0    S+   23:48   0:00      \_ grep --color=auto java
buildus+  322474  0.1  0.6 2931912 49748 ?       Sl   Apr17   4:45 /usr/lib/jvm/java-11-amazon-corretto/bin/java -ea -Xms16m -Xmx64m -cp ../launcher/lib/launcher.jar jetbrains.buildServer.agent.Launcher -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=../logs/agent_teamcity-agent.hprof -ea -Xmx512m -Dteamcity_logs=../logs/ -Dlog4j.configuration=file:../conf/teamcity-agent-log4j.xml jetbrains.buildServer.agent.AgentMain -file ../conf/buildAgent.properties
buildus+  322499 35.6  5.6 4271360 438788 ?      Sl   Apr17 988:48  \_ /usr/lib/jvm/java-11-amazon-corretto/bin/java -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=../logs/agent_teamcity-agent.hprof -ea -Xmx512m -Dteamcity_logs=../logs/ -Dlog4j.configuration=file:../conf/teamcity-agent-log4j.xml -classpath /opt/buildAgent/lib/jaxen-1.1.1.jar:/opt/buildAgent/lib/runtime-util.jar:/opt/buildAgent/lib/nuget-utils.jar:/opt/buildAgent/lib/joda-time.jar:/opt/buildAgent/lib/app-wrapper.jar:/opt/buildAgent/lib/patches-impl.jar:/opt/buildAgent/lib/agent-configurator.jar:/opt/buildAgent/lib/commons-codec.jar:/opt/buildAgent/lib/log4j-1.2.12-json-layout.jar:/opt/buildAgent/lib/launcher.jar:/opt/buildAgent/lib/agent-upgrade.jar:/opt/buildAgent/lib/duplicator-util.jar:/opt/buildAgent/lib/cloud-shared.jar:/opt/buildAgent/lib/commons-collections-3.2.2.jar:/opt/buildAgent/lib/jdom.jar:/opt/buildAgent/lib/buildAgent-updates-applying.jar:/opt/buildAgent/lib/spring.jar:/opt/buildAgent/lib/xz-1.8.jar:/opt/buildAgent/lib/log4j-1.2.12.jar:/opt/buildAgent/lib/xercesImpl.jar:/opt/buildAgent/lib/inspections-util.jar:/opt/buildAgent/lib/commons-compress-1.20.jar:/opt/buildAgent/lib/xmlrpc-2.0.1.jar:/opt/buildAgent/lib/commons-beanutils-core.jar:/opt/buildAgent/lib/commons-httpclient-3.1.jar:/opt/buildAgent/lib/common.jar:/opt/buildAgent/lib/trove4j.jar:/opt/buildAgent/lib/launcher-api.jar:/opt/buildAgent/lib/agent-launcher.jar:/opt/buildAgent/lib/xpp3-1.1.4c.jar:/opt/buildAgent/lib/processesTerminator.jar:/opt/buildAgent/lib/server-logging.jar:/opt/buildAgent/lib/resources_en.jar:/opt/buildAgent/lib/slf4j-log4j12-1.7.5.jar:/opt/buildAgent/lib/patches.jar:/opt/buildAgent/lib/openapi.jar:/opt/buildAgent/lib/jdk-searcher.jar:/opt/buildAgent/lib/coverage-report.jar:/opt/buildAgent/lib/commons-io-1.3.2.jar:/opt/buildAgent/lib/common-runtime.jar:/opt/buildAgent/lib/idea-settings.jar:/opt/buildAgent/lib/serviceMessages.jar:/opt/buildAgent/lib/trove-3.0.3.jar:/opt/buildAgent/lib/xstream-1.4.11.1-custom.jar:/opt/buildAgent/lib/xml-rpc-wrapper.jar:/opt/buildAgent/lib/agent.jar:/opt/buildAgent/lib/common-impl.jar:/opt/buildAgent/lib/spring-scripting/spring-scripting-groovy.jar:/opt/buildAgent/lib/spring-scripting/spring-scripting-bsh.jar:/opt/buildAgent/lib/spring-scripting/spring-scripting-jruby.jar:/opt/buildAgent/lib/ehcache-1.7.2.jar:/opt/buildAgent/lib/agent-installer-ui.jar:/opt/buildAgent/lib/coverage-agent-common.jar:/opt/buildAgent/lib/annotations.jar:/opt/buildAgent/lib/agent-openapi.jar:/opt/buildAgent/lib/commons-logging.jar:/opt/buildAgent/lib/slf4j-api-1.7.5.jar:/opt/buildAgent/lib/messages.jar:/opt/buildAgent/lib/util.jar:/opt/buildAgent/lib/ehcache-1.6.0-patch.jar:/opt/buildAgent/lib/freemarker.jar:/opt/buildAgent/lib/gson.jar jetbrains.buildServer.agent.AgentMain -file ../conf/buildAgent.properties -launcher.version 92350
buildus+ 1156051  0.1  0.0      0     0 ?        Zl   Mar30  53:22 [java] <defunct>
buildus+    2931  0.1  0.6 2999496 53324 ?       Sl   Mar29  39:57  \_ /usr/lib/jvm/java-11-amazon-corretto/bin/java -ea -Xms16m -Xmx64m -cp ../launcher/lib/launcher.jar jetbrains.buildServer.agent.Launcher -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=../logs/agent_teamcity-agent.hprof -ea -Xmx512m -Dteamcity_logs=../logs/ -Dlog4j.configuration=file:../conf/teamcity-agent-log4j.xml jetbrains.buildServer.agent.AgentMain -file ../conf/buildAgent.properties
builduser@jre-rpi4b8g-00:~$ exit
logout
Connection to jre-rpi4b8g-00.myo.labs.intellij.net closed.
unit-929:~ vprovodin$ ssh builduser@intellij-ubuntu2004-jre-311
The authenticity of host 'intellij-ubuntu2004-jre-311 (172.31.151.205)' can't be established.
ECDSA key fingerprint is SHA256:rB5sroaDkBkuaA0PWmxwK7zkqfgoo3ArkI4wToKK8TY.
Are you sure you want to continue connecting (yes/no/[fingerprint])? yes
Warning: Permanently added 'intellij-ubuntu2004-jre-311,172.31.151.205' (ECDSA) to the list of known hosts.
Welcome to Ubuntu 20.04.1 LTS (GNU/Linux 5.4.0-56-generic x86_64)

 * Documentation:  https://help.ubuntu.com
 * Management:     https://landscape.canonical.com
 * Support:        https://ubuntu.com/advantage

0 updates can be installed immediately.
0 of these updates are security updates.


The list of available updates is more than a week old.
To check for new updates run: sudo apt update
builduser@intellij-ubuntu2004-jre-311:~$ ls -l /opt/
allure-cli/                             filebeat-6.2.4-linux-x86_64/            microsoft/                              nvm/
buildAgent/                             filebeat-6.2.4-linux-x86_64.tar.gz      node_exporter/                          teamcity_agent_exporter.sh
containerd/                             go/                                     node_exporter-1.0.1.linux-amd64/
filebeat/                               gradle/                                 node_exporter-1.0.1.linux-amd64.tar.gz
builduser@intellij-ubuntu2004-jre-311:~$ ls -l /opt/buildAgent/
BUILD_92350         conf/               launcher/           logs/               service.properties  temp/               work/
bin/                contrib/            lib/                plugins/            system/             tools/
builduser@intellij-ubuntu2004-jre-311:~$ ls -l /opt/buildAgent/work/^C
builduser@intellij-ubuntu2004-jre-311:~$ cd /opt/buildAgent/work/efb45cc305c2e813/
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ ls -l
total 159644
drwxrwxr-x  4 builduser builduser      4096 Apr 19 03:49 JTreport
drwxrwxr-x  9 builduser builduser      4096 Apr 19 03:03 JTwork
drwxr-xr-x  7 builduser builduser      4096 Apr 19 02:44 jbr
-rw-rw-r--  1 builduser builduser 163439627 Apr 19 03:01 jbr_dcevm-11_0_10-linux-x64-b1428.3.tar.gz
drwxrwxr-x  7 builduser builduser      4096 Apr 19 03:01 jtreg
drwxrwxr-x 25 builduser builduser      4096 Apr 19 02:47 make
drwxrwxr-x 82 builduser builduser      4096 Apr 19 02:47 src
drwxrwxr-x 14 builduser builduser      4096 Apr 19 02:47 test
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ cat JTwork/
classes/        extraPropDefns/ javax/          jb/             jtData/         patches/        scratch/
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ cat JTwork/jb/java/jcef/HandleJSQueryTest3314.jtr
#Test Results (version 2)
#Mon Apr 19 03:49:00 MSK 2021
#-----testdescription-----
$file=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
$root=/opt/buildAgent/work/efb45cc305c2e813/test/jdk
keywords=shell
maxTimeout=300
run=USER_SPECIFIED shell/timeout\=300 HandleJSQueryTest3314.sh\n
source=HandleJSQueryTest3314.sh
title=Regression test for JBR-3314 It executes the test HandleJSQueryTest.java in a loop till a crash happens or number of iterations exceeds a limit.

#-----environment-----

#-----testresult-----
description=file\:/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
elapsed=1192 0\:00\:01.192
end=Mon Apr 19 03\:49\:00 MSK 2021
environment=regtest
execStatus=Failed. Execution failed\: exit code 2
harnessLoaderMode=Classpath Loader
harnessVariety=Full Bundle
hostname=intellij-ubuntu2004-jre-311.labs.intellij.net
javatestOS=Linux 5.4.0-56-generic (amd64)
javatestVersion=6.0-ea+b08-2020-03-31
jtregVersion=jtreg 5.0 dev 774
script=com.sun.javatest.regtest.exec.RegressionScript
sections=script_messages shell
start=Mon Apr 19 03\:48\:59 MSK 2021
test=jb/java/jcef/HandleJSQueryTest3314.sh
testJDK=/opt/buildAgent/work/efb45cc305c2e813/jbr
totalTime=1197
user.name=builduser
work=/opt/buildAgent/work/efb45cc305c2e813/JTwork/jb/java/jcef

#section:script_messages
----------messages:(4/285)----------
JDK under test: /opt/buildAgent/work/efb45cc305c2e813/jbr
openjdk version "11.0.10" 2021-01-19
OpenJDK Runtime Environment JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3)
Dynamic Code Evolution 64-Bit Server VM JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3, mixed mode)

#section:shell
----------messages:(3/149)----------
command: shell HandleJSQueryTest3314.sh
reason: User specified action: run shell/timeout=300 HandleJSQueryTest3314.sh
elapsed time (seconds): 1.096
----------System.out:(1/26)----------
number of iterations: 100
----------System.err:(5/454)----------
/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh: 44: pushd: not found
Note: ./JBCefBrowser.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh: 47: popd: not found
/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh: 49: Syntax error: Bad for loop variable
----------rerun:(24/1049)*----------
cd /opt/buildAgent/work/efb45cc305c2e813/JTwork/scratch && \\
DISPLAY=:0 \\
GNOME_DESKTOP_SESSION_ID=this-is-deprecated \\
HOME=/home/builduser \\
LANG=en_US \\
PATH=/bin:/usr/bin:/usr/sbin \\
TESTFILE=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh \\
TESTSRC=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTSRCPATH=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTCLASSES=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
TESTCLASSPATH=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
COMPILEJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTVMOPTS= \\
TESTTOOLVMOPTS= \\
TESTJAVACOPTS= \\
TESTJAVAOPTS= \\
TESTTIMEOUTFACTOR=1.0 \\
TESTROOT=/opt/buildAgent/work/efb45cc305c2e813/test/jdk \\
FS=/ \\
PS=: \\
NULL=/dev/null \\
    sh \\
        /opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
result: Failed. Execution failed: exit code 2


test result: Failed. Execution failed: exit code 2
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ vi test/jdk/jb/java/jcef/H
HandleJSQueryTest.java          HandleJSQueryTest3314.sh        HwFacadeWindowNoFrontTest.java
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ vi test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ cat JTwork/jb/java/jcef/HandleJSQueryTest3314.jtr
#Test Results (version 2)
#Mon Apr 19 03:51:03 MSK 2021
#-----testdescription-----
$file=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
$root=/opt/buildAgent/work/efb45cc305c2e813/test/jdk
keywords=shell
maxTimeout=300
run=USER_SPECIFIED shell/timeout\=300 HandleJSQueryTest3314.sh\n
source=HandleJSQueryTest3314.sh
title=Regression test for JBR-3314 It executes the test HandleJSQueryTest.java in a loop till a crash happens or number of iterations exceeds a limit.

#-----environment-----

#-----testresult-----
description=file\:/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
elapsed=1173 0\:00\:01.173
end=Mon Apr 19 03\:51\:03 MSK 2021
environment=regtest
execStatus=Failed. Execution failed\: exit code 2
harnessLoaderMode=Classpath Loader
harnessVariety=Full Bundle
hostname=intellij-ubuntu2004-jre-311.labs.intellij.net
javatestOS=Linux 5.4.0-56-generic (amd64)
javatestVersion=6.0-ea+b08-2020-03-31
jtregVersion=jtreg 5.0 dev 774
script=com.sun.javatest.regtest.exec.RegressionScript
sections=script_messages shell
start=Mon Apr 19 03\:51\:01 MSK 2021
test=jb/java/jcef/HandleJSQueryTest3314.sh
testJDK=/opt/buildAgent/work/efb45cc305c2e813/jbr
totalTime=1178
user.name=builduser
work=/opt/buildAgent/work/efb45cc305c2e813/JTwork/jb/java/jcef

#section:script_messages
----------messages:(4/285)----------
JDK under test: /opt/buildAgent/work/efb45cc305c2e813/jbr
openjdk version "11.0.10" 2021-01-19
OpenJDK Runtime Environment JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3)
Dynamic Code Evolution 64-Bit Server VM JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3, mixed mode)

#section:shell
----------messages:(3/149)----------
command: shell HandleJSQueryTest3314.sh
reason: User specified action: run shell/timeout=300 HandleJSQueryTest3314.sh
elapsed time (seconds): 1.075
----------System.out:(1/26)----------
number of iterations: 100
----------System.err:(3/241)----------
Note: ./JBCefBrowser.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh: 49: Syntax error: Bad for loop variable
----------rerun:(24/1049)*----------
cd /opt/buildAgent/work/efb45cc305c2e813/JTwork/scratch && \\
DISPLAY=:0 \\
GNOME_DESKTOP_SESSION_ID=this-is-deprecated \\
HOME=/home/builduser \\
LANG=en_US \\
PATH=/bin:/usr/bin:/usr/sbin \\
TESTFILE=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh \\
TESTSRC=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTSRCPATH=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTCLASSES=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
TESTCLASSPATH=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
COMPILEJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTVMOPTS= \\
TESTTOOLVMOPTS= \\
TESTJAVACOPTS= \\
TESTJAVAOPTS= \\
TESTTIMEOUTFACTOR=1.0 \\
TESTROOT=/opt/buildAgent/work/efb45cc305c2e813/test/jdk \\
FS=/ \\
PS=: \\
NULL=/dev/null \\
    sh \\
        /opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
result: Failed. Execution failed: exit code 2


test result: Failed. Execution failed: exit code 2
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ vi test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ cat JTwork/jb/java/jcef/HandleJSQueryTest3314.jtr
#Test Results (version 2)
#Mon Apr 19 03:51:36 MSK 2021
#-----testdescription-----
$file=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
$root=/opt/buildAgent/work/efb45cc305c2e813/test/jdk
keywords=shell
maxTimeout=300
run=USER_SPECIFIED shell/timeout\=300 HandleJSQueryTest3314.sh\n
source=HandleJSQueryTest3314.sh
title=Regression test for JBR-3314 It executes the test HandleJSQueryTest.java in a loop till a crash happens or number of iterations exceeds a limit.

#-----environment-----

#-----testresult-----
description=file\:/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
elapsed=1194 0\:00\:01.194
end=Mon Apr 19 03\:51\:36 MSK 2021
environment=regtest
execStatus=Failed. Execution failed\: exit code 2
harnessLoaderMode=Classpath Loader
harnessVariety=Full Bundle
hostname=intellij-ubuntu2004-jre-311.labs.intellij.net
javatestOS=Linux 5.4.0-56-generic (amd64)
javatestVersion=6.0-ea+b08-2020-03-31
jtregVersion=jtreg 5.0 dev 774
script=com.sun.javatest.regtest.exec.RegressionScript
sections=script_messages shell
start=Mon Apr 19 03\:51\:35 MSK 2021
test=jb/java/jcef/HandleJSQueryTest3314.sh
testJDK=/opt/buildAgent/work/efb45cc305c2e813/jbr
totalTime=1199
user.name=builduser
work=/opt/buildAgent/work/efb45cc305c2e813/JTwork/jb/java/jcef

#section:script_messages
----------messages:(4/285)----------
JDK under test: /opt/buildAgent/work/efb45cc305c2e813/jbr
openjdk version "11.0.10" 2021-01-19
OpenJDK Runtime Environment JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3)
Dynamic Code Evolution 64-Bit Server VM JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3, mixed mode)

#section:shell
----------messages:(3/149)----------
command: shell HandleJSQueryTest3314.sh
reason: User specified action: run shell/timeout=300 HandleJSQueryTest3314.sh
elapsed time (seconds): 1.095
----------System.out:(1/26)----------
number of iterations: 100
----------System.err:(3/241)----------
Note: ./JBCefBrowser.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh: 49: Syntax error: Bad for loop variable
----------rerun:(24/1049)*----------
cd /opt/buildAgent/work/efb45cc305c2e813/JTwork/scratch && \\
DISPLAY=:0 \\
GNOME_DESKTOP_SESSION_ID=this-is-deprecated \\
HOME=/home/builduser \\
LANG=en_US \\
PATH=/bin:/usr/bin:/usr/sbin \\
TESTFILE=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh \\
TESTSRC=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTSRCPATH=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTCLASSES=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
TESTCLASSPATH=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
COMPILEJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTVMOPTS= \\
TESTTOOLVMOPTS= \\
TESTJAVACOPTS= \\
TESTJAVAOPTS= \\
TESTTIMEOUTFACTOR=1.0 \\
TESTROOT=/opt/buildAgent/work/efb45cc305c2e813/test/jdk \\
FS=/ \\
PS=: \\
NULL=/dev/null \\
    sh \\
        /opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
result: Failed. Execution failed: exit code 2


test result: Failed. Execution failed: exit code 2
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ vi test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ cat JTwork/jb/java/jcef/HandleJSQueryTest3314.jtr
#Test Results (version 2)
#Mon Apr 19 03:52:38 MSK 2021
#-----testdescription-----
$file=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
$root=/opt/buildAgent/work/efb45cc305c2e813/test/jdk
keywords=shell
maxTimeout=300
run=USER_SPECIFIED shell/timeout\=300 HandleJSQueryTest3314.sh\n
source=HandleJSQueryTest3314.sh
title=Regression test for JBR-3314 It executes the test HandleJSQueryTest.java in a loop till a crash happens or number of iterations exceeds a limit.

#-----environment-----

#-----testresult-----
description=file\:/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
elapsed=1230 0\:00\:01.230
end=Mon Apr 19 03\:52\:38 MSK 2021
environment=regtest
execStatus=Failed. Execution failed\: exit code 2
harnessLoaderMode=Classpath Loader
harnessVariety=Full Bundle
hostname=intellij-ubuntu2004-jre-311.labs.intellij.net
javatestOS=Linux 5.4.0-56-generic (amd64)
javatestVersion=6.0-ea+b08-2020-03-31
jtregVersion=jtreg 5.0 dev 774
script=com.sun.javatest.regtest.exec.RegressionScript
sections=script_messages shell
start=Mon Apr 19 03\:52\:37 MSK 2021
test=jb/java/jcef/HandleJSQueryTest3314.sh
testJDK=/opt/buildAgent/work/efb45cc305c2e813/jbr
totalTime=1235
user.name=builduser
work=/opt/buildAgent/work/efb45cc305c2e813/JTwork/jb/java/jcef

#section:script_messages
----------messages:(4/285)----------
JDK under test: /opt/buildAgent/work/efb45cc305c2e813/jbr
openjdk version "11.0.10" 2021-01-19
OpenJDK Runtime Environment JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3)
Dynamic Code Evolution 64-Bit Server VM JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3, mixed mode)

#section:shell
----------messages:(3/149)----------
command: shell HandleJSQueryTest3314.sh
reason: User specified action: run shell/timeout=300 HandleJSQueryTest3314.sh
elapsed time (seconds): 1.134
----------System.out:(3/90)----------
number of iterations: 100
curdir=/opt/buildAgent/work/efb45cc305c2e813/JTwork/scratch
100
----------System.err:(3/241)----------
Note: ./JBCefBrowser.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh: 51: Syntax error: Bad for loop variable
----------rerun:(24/1049)*----------
cd /opt/buildAgent/work/efb45cc305c2e813/JTwork/scratch && \\
DISPLAY=:0 \\
GNOME_DESKTOP_SESSION_ID=this-is-deprecated \\
HOME=/home/builduser \\
LANG=en_US \\
PATH=/bin:/usr/bin:/usr/sbin \\
TESTFILE=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh \\
TESTSRC=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTSRCPATH=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTCLASSES=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
TESTCLASSPATH=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
COMPILEJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTVMOPTS= \\
TESTTOOLVMOPTS= \\
TESTJAVACOPTS= \\
TESTJAVAOPTS= \\
TESTTIMEOUTFACTOR=1.0 \\
TESTROOT=/opt/buildAgent/work/efb45cc305c2e813/test/jdk \\
FS=/ \\
PS=: \\
NULL=/dev/null \\
    sh \\
        /opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
result: Failed. Execution failed: exit code 2


test result: Failed. Execution failed: exit code 2
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ vi test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ cat JTwork/jb/java/jcef/HandleJSQueryTest3314.jtr
#Test Results (version 2)
#Mon Apr 19 03:52:38 MSK 2021
#-----testdescription-----
$file=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
$root=/opt/buildAgent/work/efb45cc305c2e813/test/jdk
keywords=shell
maxTimeout=300
run=USER_SPECIFIED shell/timeout\=300 HandleJSQueryTest3314.sh\n
source=HandleJSQueryTest3314.sh
title=Regression test for JBR-3314 It executes the test HandleJSQueryTest.java in a loop till a crash happens or number of iterations exceeds a limit.

#-----environment-----

#-----testresult-----
description=file\:/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
elapsed=1230 0\:00\:01.230
end=Mon Apr 19 03\:52\:38 MSK 2021
environment=regtest
execStatus=Failed. Execution failed\: exit code 2
harnessLoaderMode=Classpath Loader
harnessVariety=Full Bundle
hostname=intellij-ubuntu2004-jre-311.labs.intellij.net
javatestOS=Linux 5.4.0-56-generic (amd64)
javatestVersion=6.0-ea+b08-2020-03-31
jtregVersion=jtreg 5.0 dev 774
script=com.sun.javatest.regtest.exec.RegressionScript
sections=script_messages shell
start=Mon Apr 19 03\:52\:37 MSK 2021
test=jb/java/jcef/HandleJSQueryTest3314.sh
testJDK=/opt/buildAgent/work/efb45cc305c2e813/jbr
totalTime=1235
user.name=builduser
work=/opt/buildAgent/work/efb45cc305c2e813/JTwork/jb/java/jcef

#section:script_messages
----------messages:(4/285)----------
JDK under test: /opt/buildAgent/work/efb45cc305c2e813/jbr
openjdk version "11.0.10" 2021-01-19
OpenJDK Runtime Environment JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3)
Dynamic Code Evolution 64-Bit Server VM JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3, mixed mode)

#section:shell
----------messages:(3/149)----------
command: shell HandleJSQueryTest3314.sh
reason: User specified action: run shell/timeout=300 HandleJSQueryTest3314.sh
elapsed time (seconds): 1.134
----------System.out:(3/90)----------
number of iterations: 100
curdir=/opt/buildAgent/work/efb45cc305c2e813/JTwork/scratch
100
----------System.err:(3/241)----------
Note: ./JBCefBrowser.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh: 51: Syntax error: Bad for loop variable
----------rerun:(24/1049)*----------
cd /opt/buildAgent/work/efb45cc305c2e813/JTwork/scratch && \\
DISPLAY=:0 \\
GNOME_DESKTOP_SESSION_ID=this-is-deprecated \\
HOME=/home/builduser \\
LANG=en_US \\
PATH=/bin:/usr/bin:/usr/sbin \\
TESTFILE=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh \\
TESTSRC=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTSRCPATH=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTCLASSES=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
TESTCLASSPATH=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
COMPILEJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTVMOPTS= \\
TESTTOOLVMOPTS= \\
TESTJAVACOPTS= \\
TESTJAVAOPTS= \\
TESTTIMEOUTFACTOR=1.0 \\
TESTROOT=/opt/buildAgent/work/efb45cc305c2e813/test/jdk \\
FS=/ \\
PS=: \\
NULL=/dev/null \\
    sh \\
        /opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
result: Failed. Execution failed: exit code 2


test result: Failed. Execution failed: exit code 2
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ vi test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ cat JTwork/jb/java/jcef/HandleJSQueryTest3314.jtr
#Test Results (version 2)
#Mon Apr 19 05:23:58 MSK 2021
#-----testdescription-----
$file=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
$root=/opt/buildAgent/work/efb45cc305c2e813/test/jdk
keywords=shell
maxTimeout=300
run=USER_SPECIFIED shell/timeout\=300 HandleJSQueryTest3314.sh\n
source=HandleJSQueryTest3314.sh
title=Regression test for JBR-3314 It executes the test HandleJSQueryTest.java in a loop till a crash happens or number of iterations exceeds a limit.

#-----environment-----

#-----testresult-----
description=file\:/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
elapsed=1255 0\:00\:01.255
end=Mon Apr 19 05\:23\:58 MSK 2021
environment=regtest
execStatus=Passed. Execution successful
harnessLoaderMode=Classpath Loader
harnessVariety=Full Bundle
hostname=intellij-ubuntu2004-jre-311.labs.intellij.net
javatestOS=Linux 5.4.0-56-generic (amd64)
javatestVersion=6.0-ea+b08-2020-03-31
jtregVersion=jtreg 5.0 dev 774
script=com.sun.javatest.regtest.exec.RegressionScript
sections=script_messages shell
start=Mon Apr 19 05\:23\:56 MSK 2021
test=jb/java/jcef/HandleJSQueryTest3314.sh
testJDK=/opt/buildAgent/work/efb45cc305c2e813/jbr
totalTime=1264
user.name=builduser
work=/opt/buildAgent/work/efb45cc305c2e813/JTwork/jb/java/jcef

#section:script_messages
----------messages:(4/285)----------
JDK under test: /opt/buildAgent/work/efb45cc305c2e813/jbr
openjdk version "11.0.10" 2021-01-19
OpenJDK Runtime Environment JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3)
Dynamic Code Evolution 64-Bit Server VM JBR-11.0.10.11-1428.3-dcevm (build 11.0.10+11-b1428.3, mixed mode)

#section:shell
----------messages:(3/149)----------
command: shell HandleJSQueryTest3314.sh
reason: User specified action: run shell/timeout=300 HandleJSQueryTest3314.sh
elapsed time (seconds): 1.156
----------System.out:(3/137)----------
number of iterations: 100
curdir=/opt/buildAgent/work/efb45cc305c2e813/JTwork/scratch
PASSED: Test did never crash during 100 iterations
----------System.err:(3/225)----------
Note: ./JBCefBrowser.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh: 50: [: Illegal number:
----------rerun:(24/1049)*----------
cd /opt/buildAgent/work/efb45cc305c2e813/JTwork/scratch && \\
DISPLAY=:0 \\
GNOME_DESKTOP_SESSION_ID=this-is-deprecated \\
HOME=/home/builduser \\
LANG=en_US \\
PATH=/bin:/usr/bin:/usr/sbin \\
TESTFILE=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh \\
TESTSRC=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTSRCPATH=/opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef \\
TESTCLASSES=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
TESTCLASSPATH=/opt/buildAgent/work/efb45cc305c2e813/JTwork/classes/jb/java/jcef/HandleJSQueryTest3314.d \\
COMPILEJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTJAVA=/opt/buildAgent/work/efb45cc305c2e813/jbr \\
TESTVMOPTS= \\
TESTTOOLVMOPTS= \\
TESTJAVACOPTS= \\
TESTJAVAOPTS= \\
TESTTIMEOUTFACTOR=1.0 \\
TESTROOT=/opt/buildAgent/work/efb45cc305c2e813/test/jdk \\
FS=/ \\
PS=: \\
NULL=/dev/null \\
    sh \\
        /opt/buildAgent/work/efb45cc305c2e813/test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
result: Passed. Execution successful


test result: Passed. Execution successful
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ vi test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh
builduser@intellij-ubuntu2004-jre-311:/opt/buildAgent/work/efb45cc305c2e813$ vi test/jdk/jb/java/jcef/HandleJSQueryTest3314.sh

# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# @test
# @summary Regression test for JBR-3314
#          It executes the test HandleJSQueryTest.java in a loop till a crash happens or number of iterations
#          exceeds a limit.
#          Number of iterations can be specified via the environment variable RUN_NUMBER, by default it is set to 100.
# @run shell/timeout=300 HandleJSQueryTest3314.sh

RUNS_NUMBER=${RUN_NUMBER:-100}
echo "number of iterations: $RUNS_NUMBER"

if [ -z "${TESTSRC}" ]; then
  echo "TESTSRC undefined: set to ."
  TESTSRC=.
fi

if [ -z "${TESTCLASSES}" ]; then
  echo "TESTCLASSES undefined: set to ."
  TESTCLASSES=.
fi

if [ -z "${TESTJAVA}" ]; then
  echo "TESTJAVA undefined: testing cancelled"
  exit 1
fi

curdir=$(pwd)
cd ${TESTSRC}
${TESTJAVA}/bin/javac -d ${TESTCLASSES} HandleJSQueryTest.java
cd $curdir

i=0
while [ "$i" -le "$RUNS_NUMBER" ]; do
    echo "iteration - $i"
    ${TESTJAVA}/bin/java -cp ${TESTCLASSES} HandleJSQueryTest
    exit_code=$?
    echo "exit_xode=$exit_code"
    if [ $exit_code -ne "0" ]; then
      [[ $exit_code -eq "134" ]] && echo "FAILED: Test crashed" && exit $exit_code
      echo "Test failed because of not a crash. Execution is being conituned"
    fi
    i=$(( i + 1 ))
done
echo "PASSED: Test did never crash during 100 iterations"
exit 0
