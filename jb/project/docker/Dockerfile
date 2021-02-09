# jetbrains/runtime:jbr15env
FROM centos:7
RUN yum -y install centos-release-scl
RUN yum -y install devtoolset-8
RUN yum -y install zip bzip2 unzip tar wget make autoconf automake libtool gcc gcc-c++ libstdc++-devel alsa-devel cups-devel xorg-x11-devel libjpeg62-devel giflib-devel freetype-devel file which libXtst-devel libXt-devel libXrender-devel alsa-lib-devel fontconfig-devel libXrandr-devel libXi-devel git
# Install Java 15
RUN wget  https://cdn.azul.com/zulu/bin/zulu15.27.17-ca-jdk15.0.0-linux_x64.tar.gz \
-O - | tar xz -C /
RUN mv /zulu15.27.17-ca-jdk15.0.0-linux_x64 /jdk15.0.0
ENV PATH /opt/rh/devtoolset-8/root/usr/bin:$PATH
RUN mkdir .git
RUN git config user.email "builduser@jetbrains.com"
RUN git config user.name "builduser"
