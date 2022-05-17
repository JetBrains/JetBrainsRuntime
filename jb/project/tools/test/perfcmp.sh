#!/bin/bash

set -euo pipefail
set -x

usage ()
{
echo "Usage: perfcmp.sh [options] <test_results_cur> <test_results_ref> <results> <test_prefix> <noHeaders>"
echo "Options:"
echo -e " -h, --help\tdisplay this help"
echo -e " -tc\tprint teacmity statistic"
echo -e "test_results_cur - the file with  metrics values for the current measuring"
echo -e "test_results_ref - the file with metrics values for the reference measuring"
echo -e "results - results of comaprison"
echo -e "test_prefix - specifys measuring type, makes sense for enabled -tc, by default no prefixes"
echo -e "noHeaders - by default 1-st line contains headers"
echo -e ""
echo -e "test_results_* files content should be in csv format with header and tab separator:"
echo -e "The 1-st column is the test name"
echo -e "The 2-st column is the test value"
echo -e ""
echo -e "Example:"
echo -e "Test           Value"
echo -e "Testname       51.54"
}

while [ -n "$1" ]
do
case "$1" in
-h | --help) usage
exit 1 ;;
-tc) tc=1
  shift
  break  ;;
*) break;;
esac
done

if [[ "$#" < "3" ]]; then
    echo "Error: Invalid arguments"
    usage
    exit 1
fi

curFile=$1
refFile=$2
resFile=$3
testNamePrefix=$4
noHeaders=$5
echo $curFile
echo $refFile
echo $resFile

curValues=`cat "$curFile" | cut -f 2 | tr -d '\t'`
if [ -z $noHeaders ]; then
  curValuesHeader=`echo "$curValues" | head -n +1`_cur
  header=`cat "$refFile" | head -n +1 | awk -F'\t' -v x=$curValuesHeader '{print "  "$1"\t"$2"_ref\t"x"\tratio"}'`
  testContent=`paste -d '\t' $refFile <(echo "$curValues") | tail -n +2`
else
  testContent=`paste -d '\t' $refFile <(echo "$curValues") | tail -n +1`
fi

testContent=`echo "$testContent" | awk -F'\t' '{ if ($3>$2+$2*0.1) {print "* "$1"\t"$2"\t"$3"\t"(($2==0)?"-":$3/$2)} else {print "  "$1"\t"$2"\t"$3"\t"(($2==0)?"-":$3/$2)} }'`
if [ -z $noHeaders ]; then
  echo "$header" > $resFile
fi
echo "$testContent" >> $resFile
cat "$resFile" | tr '\t' ';' | column -t -s ';' | tee $resFile

if [ -z $tc ]; then
exit 0
fi

echo "$testContent" 2>&1 | (
    while read -r s; do
        testname=`echo "$s" | cut -f 1 | tr -d "[:space:]" |  tr -d "*"`
        duration=`echo "$s" | cut -f 3`
        failed=`echo "$s" | cut -c1 | grep -c "*"`
        echo \#\#teamcity[testStarted name=\'$testNamePrefix$testname\']
        echo "===>$s"
        echo \#\#teamcity[buildStatisticValue key=\'$testNamePrefix$testname\' value=\'$duration\']
        [ $failed -eq 1 ] && echo \#\#teamcity[testFailed name=\'$testNamePrefix$testname\' message=\'$s\']
        echo \#\#teamcity[testFinished name=\'$testNamePrefix$testname\' duration=\'$duration\']
        failed=0
    done
)