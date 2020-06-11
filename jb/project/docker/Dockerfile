# jetbrains/runtime:jbr11dev8env
FROM centos:7
RUN yum -y install centos-release-scl
RUN yum -y install devtoolset-8
RUN yum -y install zip bzip2 unzip tar wget make autoconf automake libtool alsa-devel cups-devel xorg-x11-devel libjpeg62-devel giflib-devel freetype-devel file which libXtst-devel libXt-devel libXrender-devel alsa-lib-devel fontconfig-devel libXrandr-devel libXi-devel git
# Install Java 11
RUN wget https://bintray.com/jetbrains/intellij-jbr/download_file?file_path=jbrsdk-11_0_3-linux-x64-b360.2.tar.gz \
-O - | tar xz -C /
ENV JAVA_HOME /jbrsdk
ENV PATH $JAVA_HOME/bin:/opt/rh/devtoolset-8/root/usr/bin:$PATH
RUN mkdir .git
RUN git config user.email "builduser@jetbrains.com"
RUN git config user.name "builduser"
