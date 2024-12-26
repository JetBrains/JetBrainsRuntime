#!/bin/bash

function fatal {
    echo "FATAL ERROR: " $1
    exit 1
}

DOCKERFILE=Dockerfile.oraclelinux
YUM_EXTRA=--enablerepo=ol8_codeready_builder
DATE=$(date +"%Y-%m-%d")

echo "Checking for updates on $DATE..."

yum install -q -y yum-utils
yum makecache $YUM_EXTRA
[ $? != 0 ] && fatal "yum makecache"

UPDATES=$(yum check-update -q -C 2>/dev/null | awk '/^\S/ {print $1}' | sed 's/\.[^.]*$//')

NUPDATES=0
for P in $UPDATES; do ((NUPDATES++)); done
[ $NUPDATES -eq 0 ] && echo "NO UPDATES on $DATE" && exit 0

echo "NEED TO UPDATE IMAGE"
echo "$NUPDATES packages updated: " $UPDATES

[ ! -f $DOCKERFILE ] && fatal "no $DOCKERFILE"

DNUPDATES=0
for P in $UPDATES; do
    N=$(grep -c "$P-[0-9]" $DOCKERFILE)
    [ $N -gt 1 ] && fatal "More than one match for $P in $DOCKERFILE, can't create a patch"
    DP=$(grep "$P-[0-9]" $DOCKERFILE)
    if [ -n "$DP" ]; then
        ((DNUPDATES++))
    fi
done

[ $DNUPDATES -eq 0 ] && echo "No need to update $DOCKERFILE" && exit 0

F=${DOCKERFILE}.new
rm -f $F
cp $DOCKERFILE $F
[ $? != 0 ] && fatal "copying $DOCKERFILE"

for P in $UPDATES; do
    DP=$(grep "$P-[0-9]" $DOCKERFILE | awk '{print $1}')
    if [ -n "$DP" ]; then
        LATEST=$(repoquery -q $YUM_EXTRA --latest-limit=1 --queryformat="%{name}-%{version}-%{release}" $P)
        [ -z "$LATEST" ] && fatal "Can't determine the latest version of $P"
        echo "$DP -> $LATEST"
        sed -i "s/\b$DP\b/$LATEST/g" "$F"
    fi
done

diff $DOCKERFILE $F > /dev/null 2>&1
[ $? -eq 0 ] && echo "No changes to $DOCKERFILE, just update the image" && exit 0

echo "Apply this patch to $DOCKERFILE:"
diff -C 3 $DOCKERFILE $F