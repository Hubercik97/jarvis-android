const test=require('node:test');const assert=require('node:assert/strict');
test('Polish commands resolve to explicit local capabilities',async()=>{const {localCommand}=await import('../src/commands.ts');
 assert.equal(localCommand('Jarvis, otwórz Chrome.'),'open_chrome');assert.equal(localCommand('Otwórz folder Dokumenty'),'open_documents');assert.equal(localCommand('Jarvis, otwórz mój projekt na GitHubie'),'open_project');
});
test('negated, embedded and shell-like text never becomes an operating-system action',async()=>{const {localCommand}=await import('../src/commands.ts');for(const text of ['nie otwieraj Chrome','powiedz otwórz Chrome','otwórz Chrome && calc','usuń moje pliki','otwórz C:\\Windows','wyślij wiadomość'])assert.equal(localCommand(text),null);});
test('project status is read through a fixed cloud capability',async()=>{const {isProjectStatus}=await import('../src/commands.ts');assert.equal(isProjectStatus('Jarvis, sprawdź stan mojego projektu'),true);assert.equal(isProjectStatus('Pokaż status projektu'),true);assert.equal(isProjectStatus('sprawdź https://evil.example'),false);});
