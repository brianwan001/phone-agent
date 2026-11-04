package tech.handsy.phoneagent;

interface IShellService {
    int runTap(int x, int y);
    String runCmd(String cmd);
    void destroy();
}
