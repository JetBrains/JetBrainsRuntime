#!/usr/sbin/dtrace -qs

hotspot*:::method-entry
{
 this->class = (char *) copyin(arg1, arg2 + 1);
 this->class[arg2] = '\0';

 this->method = (char *) copyin(arg3, arg4 + 1);
 this->method[arg4] = '\0';
 @calls[stringof(this->class) == "sun/lwawt/macosx/LWCToolkit" ? stringof(this->class) : " ", stringof(this->class) == "sun/lwawt/macosx/LWCToolkit" ? stringof(this->method) : " "] = count();
}
dtrace:::END
{

 printa(@calls);
}