# jetbrains/runtime:jbr15env
FROM centos:7
RUN yum -y install centos-release-scl
RUN yum -y install devtoolset-8
RUN yum -y install zip bzip2 unzip tar wget make autoconf automake libtool alsa-devel cups-devel xorg-x11-devel libjpeg62-devel giflib-devel freetype-devel file which libXtst-devel libXt-devel libXrender-devel alsa-lib-devel fontconfig-devel libXrandr-devel libXi-devel git
# Install Java 11
RUN wget https://download.java.net/java/GA/jdk14.0.1/664493ef4a6946b186ff29eb326336a2/7/GPL/openjdk-14.0.1_linux-x64_bin.tar.gz \
-O - | tar xz -C /
ENV JAVA_HOME /jbrsdk
ENV PATH $JAVA_HOME/bin:/opt/rh/devtoolset-8/root/usr/bin:$PATH
