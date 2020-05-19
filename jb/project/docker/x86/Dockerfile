FROM i386/ubuntu:xenial

RUN linux32 apt-get update && apt-get install -y --no-install-recommends apt-utils
RUN linux32 apt-get -y install file build-essential zip unzip tar wget curl libx11-dev libxext-dev \
  libxrender-dev libxrandr-dev libxtst-dev libxt-dev libcups2-dev libasound2-data \
  libpng12-0 libasound2 libfreetype6 libfontconfig1-dev libasound2-dev autoconf git
RUN wget https://cdn.azul.com/zulu/bin/zulu13.31.11-ca-jdk13.0.3-linux_i686.tar.gz  \
  -O - | tar xz -C /
ENV JAVA_HOME /zulu13.31.11-ca-jdk13.0.3-linux_i686
ENV PATH $JAVA_HOME/bin:$PATH
