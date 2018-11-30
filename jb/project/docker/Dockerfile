FROM centos:7

RUN yum -y install zip bzip2 unzip tar wget make autoconf automake libtool gcc gcc-c++ libstdc++-devel alsa-devel cups-devel xorg-x11-devel libjpeg62-devel giflib-devel freetype-devel file which libXtst-devel libXt-devel libXrender-devel alsa-lib-devel fontconfig-devel

# Install Java 11
RUN wget --no-check-certificate -q --no-cookies --header "Cookie: oraclelicense=accept-securebackup-cookie" "http://download.oracle.com/otn-pub/java/jdk/11.0.1+13/90cf5d8f270a4347a95050320eef3fb7/jdk-11.0.1_linux-x64_bin.tar.gz" \
-O - | tar xz -C /
ENV JAVA_HOME /jdk-11.0.1
ENV PATH $JAVA_HOME/bin:$PATH
