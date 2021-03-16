# jetbrains/runtime:jbr11dev8env
FROM centos:7
RUN yum -y install centos-release-scl
RUN yum -y install devtoolset-8
RUN yum -y install zip bzip2 unzip tar wget make autoconf automake libtool alsa-devel cups-devel xorg-x11-devel libjpeg62-devel giflib-devel freetype-devel file which libXtst-devel libXt-devel libXrender-devel alsa-lib-devel fontconfig-devel libXrandr-devel libXi-devel git
# Install Java 11
RUN wget https://cdn.azul.com/zulu/bin/zulu11.45.27-ca-jdk11.0.10-linux_x64.tar.gz \
-O - | tar xz -C /
ENV JAVA_HOME /zulu11.45.27-ca-jdk11.0.10-linux_x64
ENV PATH $JAVA_HOME/bin:/opt/rh/devtoolset-8/root/usr/bin:$PATH
RUN git config --global user.email "teamcity@buildserver.intellij.net"
RUN git config --global user.name "builduser"
